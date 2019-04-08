/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.cluster.qp.executor;

import com.alipay.remoting.exception.CodecException;
import com.alipay.remoting.serialization.SerializerManager;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.iotdb.cluster.callback.BatchQPTask;
import org.apache.iotdb.cluster.callback.QPTask;
import org.apache.iotdb.cluster.callback.SingleQPTask;
import org.apache.iotdb.cluster.config.ClusterConfig;
import org.apache.iotdb.cluster.entity.raft.DataPartitionRaftHolder;
import org.apache.iotdb.cluster.entity.raft.MetadataRaftHolder;
import org.apache.iotdb.cluster.entity.raft.RaftService;
import org.apache.iotdb.cluster.exception.RaftConnectionException;
import org.apache.iotdb.cluster.qp.ClusterQPExecutor;
import org.apache.iotdb.cluster.rpc.closure.ResponseClosure;
import org.apache.iotdb.cluster.rpc.request.BasicRequest;
import org.apache.iotdb.cluster.rpc.request.DataGroupNonQueryRequest;
import org.apache.iotdb.cluster.rpc.request.MetaGroupNonQueryRequest;
import org.apache.iotdb.cluster.rpc.response.BasicResponse;
import org.apache.iotdb.cluster.rpc.response.DataGroupNonQueryResponse;
import org.apache.iotdb.cluster.rpc.response.MetaGroupNonQueryResponse;
import org.apache.iotdb.cluster.rpc.service.TSServiceClusterImpl.BatchResult;
import org.apache.iotdb.cluster.utils.RaftUtils;
import org.apache.iotdb.db.exception.PathErrorException;
import org.apache.iotdb.db.exception.ProcessorException;
import org.apache.iotdb.db.qp.logical.sys.MetadataOperator;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.DeletePlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.UpdatePlan;
import org.apache.iotdb.db.qp.physical.sys.MetadataPlan;
import org.apache.iotdb.tsfile.read.common.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle distributed non-query logic
 */
public class NonQueryExecutor extends ClusterQPExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(NonQueryExecutor.class);

  private static final String OPERATION_NOT_SUPPORTED = "Operation %s does not support";

  public NonQueryExecutor() {
    super();
  }

  /**
   * Execute single non query request.
   */
  public boolean processNonQuery(PhysicalPlan plan) throws ProcessorException {
    try {
      String groupId = getGroupIdFromPhysicalPlan(plan);
      return handleNonQueryRequest(groupId, plan);
    } catch (RaftConnectionException e) {
      LOGGER.error(e.getMessage());
      throw new ProcessorException("Raft connection occurs error.", e);
    } catch (InterruptedException | PathErrorException | IOException e) {
      throw new ProcessorException(e);
    }
  }

  /**
   * Execute batch statement by physical plans and update results.
   *
   * @param physicalPlans List of physical plan
   * @param batchResult batch result
   */
  public void processBatch(PhysicalPlan[] physicalPlans, BatchResult batchResult)
      throws InterruptedException {
    int[] result = batchResult.getResult();

    /** 1. Classify physical plan by group id **/
    Map<String, List<PhysicalPlan>> physicalPlansMap = new HashMap<>();
    Map<String, List<Integer>> planIndexMap = new HashMap<>();
    for (int i = 0; i < result.length; i++) {
      /** Check if the request has failed. If it has failed, ignore it. **/
      if (result[i] != Statement.EXECUTE_FAILED) {
        PhysicalPlan plan = physicalPlans[i];
        try {
          String groupId = getGroupIdFromPhysicalPlan(plan);

          if (!physicalPlansMap.containsKey(groupId)) {
            physicalPlansMap.put(groupId, new ArrayList<>());
            planIndexMap.put(groupId, new ArrayList<>());
          }
          physicalPlansMap.get(groupId).add(plan);
          planIndexMap.get(groupId).add(i);
        } catch (PathErrorException|ProcessorException e) {
          result[i] = Statement.EXECUTE_FAILED;
          batchResult.setAllSuccessful(false);
          batchResult.setBatchErrorMessage(e.getMessage());
        }
      }
    }

    /** 2. Construct Multiple Requests **/
    Map<String, QPTask> subTaskMap = new HashMap<>();
    for (Entry<String, List<PhysicalPlan>> entry : physicalPlansMap.entrySet()) {
      String groupId = entry.getKey();
      SingleQPTask singleQPTask;
      BasicRequest request;
      try {
        if(groupId.equals(ClusterConfig.METADATA_GROUP_ID)){
          request = new MetaGroupNonQueryRequest(groupId, entry.getValue());
        }else {
          request = new DataGroupNonQueryRequest(groupId, entry.getValue());
        }
        singleQPTask = new SingleQPTask(false, request);
        subTaskMap.put(groupId, singleQPTask);
      } catch (IOException e) {
        batchResult.setAllSuccessful(false);
        batchResult.setBatchErrorMessage(e.getMessage());
        for (int index : planIndexMap.get(groupId)) {
          result[index] = Statement.EXECUTE_FAILED;
        }
      }
    }

    /** 3. Execute Multiple Tasks **/
    BatchQPTask task = new BatchQPTask(subTaskMap.size(), batchResult, subTaskMap, planIndexMap);
    currentTask = task;
    task.execute(this);
    task.await();
    batchResult.setAllSuccessful(task.isAllSuccessful());
    batchResult.setBatchErrorMessage(task.getBatchErrorMessage());
  }

  /**
   * Get group id from physical plan
   */
  public String getGroupIdFromPhysicalPlan(PhysicalPlan plan)
      throws PathErrorException, ProcessorException {
    String storageGroup;
    String groupId;
    switch (plan.getOperatorType()) {
      case DELETE:
        storageGroup = getStorageGroupFromDeletePlan((DeletePlan) plan);
        groupId = getGroupIdBySG(storageGroup);
        break;
      case UPDATE:
        Path path = ((UpdatePlan) plan).getPath();
        storageGroup = getStroageGroupByDevice(path.getDevice());
        groupId = getGroupIdBySG(storageGroup);
        break;
      case INSERT:
        storageGroup = getStroageGroupByDevice(((InsertPlan) plan).getDeviceId());
        groupId = getGroupIdBySG(storageGroup);
        break;
      case CREATE_ROLE:
      case DELETE_ROLE:
      case CREATE_USER:
      case REVOKE_USER_ROLE:
      case REVOKE_ROLE_PRIVILEGE:
      case REVOKE_USER_PRIVILEGE:
      case GRANT_ROLE_PRIVILEGE:
      case GRANT_USER_PRIVILEGE:
      case GRANT_USER_ROLE:
      case MODIFY_PASSWORD:
      case DELETE_USER:
      case LIST_ROLE:
      case LIST_USER:
      case LIST_ROLE_PRIVILEGE:
      case LIST_ROLE_USERS:
      case LIST_USER_PRIVILEGE:
      case LIST_USER_ROLES:
        groupId = ClusterConfig.METADATA_GROUP_ID;
        break;
      case LOADDATA:
        throw new UnsupportedOperationException(
            String.format(OPERATION_NOT_SUPPORTED, plan.getOperatorType()));
      case DELETE_TIMESERIES:
      case CREATE_TIMESERIES:
      case SET_STORAGE_GROUP:
      case METADATA:
        groupId = getGroupIdFromMetadataPlan((MetadataPlan) plan);
        break;
      case PROPERTY:
        throw new UnsupportedOperationException(
            String.format(OPERATION_NOT_SUPPORTED, plan.getOperatorType()));
      default:
        throw new UnsupportedOperationException(
            String.format(OPERATION_NOT_SUPPORTED, plan.getOperatorType()));
    }
    return groupId;
  }

  /**
   * Get storage group from delete plan
   */
  public String getStorageGroupFromDeletePlan(DeletePlan deletePlan)
      throws PathErrorException, ProcessorException {
    List<Path> paths = deletePlan.getPaths();
    Set<String> sgSet = new HashSet<>();
    for (Path path : paths) {
      if (mManager.checkStorageExistOfPath(path.getFullPath())) {
        sgSet.add(mManager.getFileNameByPath(path.getFullPath()));
        if (sgSet.size() > 1) {
          throw new ProcessorException(
              "Delete function in distributed iotdb only supports single storage group");
        }
      } else {
        List<String> storageGroups = mManager.getAllFileNamesByPath(path.getFullPath());
        if (!storageGroups.isEmpty()) {
          throw new ProcessorException(
              "Delete function in distributed iotdb only supports single storage group");
        } else {
          throw new ProcessorException(
              String.format("The path %s doesn't exist.", path.getFullPath()));
        }

      }
    }
    List<String> sgList = new ArrayList<>(sgSet);
    return sgList.get(0);
  }

  /**
   * Get group id from metadata plan
   */
  public String getGroupIdFromMetadataPlan(MetadataPlan metadataPlan)
      throws ProcessorException, PathErrorException {
    MetadataOperator.NamespaceType namespaceType = metadataPlan.getNamespaceType();
    Path path = metadataPlan.getPath();
    String groupId;
    switch (namespaceType) {
      case ADD_PATH:
      case DELETE_PATH:
        String deviceId = path.getDevice();
        String storageGroup = getStroageGroupByDevice(deviceId);
        groupId = getGroupIdBySG(storageGroup);
        break;
      case SET_FILE_LEVEL:
        boolean fileLevelExist = mManager.checkStorageLevelOfMTree(path.getFullPath());
        if (fileLevelExist) {
          throw new ProcessorException(
              String.format("File level %s already exists.", path.getFullPath()));
        } else {
          LOGGER.debug("Execute set storage group statement.");
          groupId = ClusterConfig.METADATA_GROUP_ID;
        }
        break;
      default:
        throw new ProcessorException("unknown namespace type:" + namespaceType);
    }
    return groupId;
  }

  /**
   * Handle non query single request by group id and physical plan
   */
  private boolean handleNonQueryRequest(String groupId, PhysicalPlan plan)
      throws IOException, RaftConnectionException, InterruptedException {
    List<PhysicalPlan> plans = new ArrayList<>();
    plans.add(plan);
    BasicRequest request;
    if (groupId.equals(ClusterConfig.METADATA_GROUP_ID)) {
      request = new MetaGroupNonQueryRequest(groupId, plans);
    } else {
      request = new DataGroupNonQueryRequest(groupId, plans);
    }
    QPTask qpTask = new SingleQPTask(false, request);
    currentTask = qpTask;
    /** Check if the plan can be executed locally. **/
    if (canHandleNonQueryByGroupId(groupId)) {
      return handleNonQueryRequestLocally(groupId, qpTask);
    } else {
      PeerId leader = RaftUtils.getLeaderPeerID(groupId);
      return asyncHandleNonQueryTask(qpTask, leader);
    }
  }

  /**
   * Handle data group request locally.
   */
  public boolean handleNonQueryRequestLocally(String groupId, QPTask qpTask)
      throws InterruptedException {
    BasicResponse response;
    RaftService service;
    if (groupId.equals(ClusterConfig.METADATA_GROUP_ID)) {
      response = MetaGroupNonQueryResponse.createEmptyInstance(groupId);
      MetadataRaftHolder metadataRaftHolder = (MetadataRaftHolder) server
          .getMetadataHolder();
      service = (RaftService) metadataRaftHolder.getService();
    } else {
      response = DataGroupNonQueryResponse.createEmptyInstance(groupId);
      DataPartitionRaftHolder dataRaftHolder = (DataPartitionRaftHolder) server
          .getDataPartitionHolderMap().get(groupId);
      service = (RaftService) dataRaftHolder.getService();
    }

    /** set task **/
    final Task task = new Task();
    ResponseClosure closure = new ResponseClosure(response, status -> {
      response.addResult(status.isOk());
      if (!status.isOk()) {
        response.setErrorMsg(status.getErrorMsg());
      }
      qpTask.run(response);
    });
    task.setDone(closure);

    BasicRequest request = qpTask.getRequest();

    /** Apply qpTask to Raft Node **/
    try {
      task.setData(ByteBuffer
          .wrap(SerializerManager.getSerializer(SerializerManager.Hessian2)
              .serialize(request)));
    } catch (final CodecException e) {
      return false;
    }
    service.getNode().apply(task);
    qpTask.await();
    return qpTask.getResponse().isSuccess();
  }

  /**
   * Async handle task by QPTask and leader id.
   *
   * @param task request QPTask
   * @param leader leader of the target raft group
   * @return request result
   */
  public boolean asyncHandleNonQueryTask(QPTask task, PeerId leader)
      throws RaftConnectionException, InterruptedException {
    BasicResponse response = asyncHandleNonQueryTaskGetRes(task, leader, 0);
    return response.isSuccess();
  }

}
