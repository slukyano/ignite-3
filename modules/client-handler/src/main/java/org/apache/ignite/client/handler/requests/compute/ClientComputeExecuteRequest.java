/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.client.handler.requests.compute;

import static org.apache.ignite.client.handler.requests.cluster.ClientClusterGetNodesRequest.packClusterNode;
import static org.apache.ignite.client.handler.requests.compute.ClientComputeGetStateRequest.packJobState;
import static org.apache.ignite.internal.client.proto.ClientComputeJobUnpacker.unpackJobArgumentWithoutMarshaller;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.apache.ignite.client.handler.NotificationSender;
import org.apache.ignite.compute.JobExecution;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.NodeNotFoundException;
import org.apache.ignite.deployment.DeploymentUnit;
import org.apache.ignite.internal.client.proto.ClientComputeJobPacker;
import org.apache.ignite.internal.client.proto.ClientMessagePacker;
import org.apache.ignite.internal.client.proto.ClientMessageUnpacker;
import org.apache.ignite.internal.compute.ComputeJobDataHolder;
import org.apache.ignite.internal.compute.IgniteComputeInternal;
import org.apache.ignite.internal.compute.MarshallerProvider;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.marshalling.Marshaller;
import org.apache.ignite.network.ClusterNode;
import org.jetbrains.annotations.Nullable;

/**
 * Compute execute request.
 */
public class ClientComputeExecuteRequest {
    /**
     * Processes the request.
     *
     * @param in Unpacker.
     * @param out Packer.
     * @param compute Compute.
     * @param cluster Cluster.
     * @param notificationSender Notification sender.
     * @return Future.
     */
    public static CompletableFuture<Void> process(
            ClientMessageUnpacker in,
            ClientMessagePacker out,
            IgniteComputeInternal compute,
            ClusterService cluster,
            NotificationSender notificationSender
    ) {
        Set<ClusterNode> candidates = unpackCandidateNodes(in, cluster);

        List<DeploymentUnit> deploymentUnits = in.unpackDeploymentUnits();
        String jobClassName = in.unpackString();
        JobExecutionOptions options = JobExecutionOptions.builder().priority(in.unpackInt()).maxRetries(in.unpackInt()).build();
        ComputeJobDataHolder arg = unpackJobArgumentWithoutMarshaller(in);

        CompletableFuture<JobExecution<ComputeJobDataHolder>> executionFut = compute.executeAsyncWithFailover(
                candidates, deploymentUnits, jobClassName, options, arg, null
        );
        sendResultAndState(executionFut, notificationSender);

        //noinspection DataFlowIssue
        return executionFut.thenCompose(execution ->
                execution.idAsync().thenAccept(jobId -> packSubmitResult(out, jobId, execution.node()))
        );
    }

    private static Set<ClusterNode> unpackCandidateNodes(ClientMessageUnpacker in, ClusterService cluster) {
        int size = in.unpackInt();

        if (size < 1) {
            throw new IllegalArgumentException("nodes must not be empty.");
        }

        Set<String> nodeNames = new HashSet<>(size);
        Set<ClusterNode> nodes = new HashSet<>(size);

        for (int i = 0; i < size; i++) {
            String nodeName = in.unpackString();
            nodeNames.add(nodeName);
            ClusterNode node = cluster.topologyService().getByConsistentId(nodeName);
            if (node != null) {
                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            throw new NodeNotFoundException(nodeNames);
        }

        return nodes;
    }

    static CompletableFuture<ComputeJobDataHolder> sendResultAndState(
            CompletableFuture<JobExecution<ComputeJobDataHolder>> executionFut,
            NotificationSender notificationSender
    ) {
        return executionFut.handle((execution, throwable) -> {
            if (throwable != null) {
                notificationSender.sendNotification(null, throwable);
                return CompletableFuture.<ComputeJobDataHolder>failedFuture(throwable);
            } else {
                return execution.resultAsync().whenComplete((val, err) ->
                        execution.stateAsync().whenComplete((state, errState) ->
                                notificationSender.sendNotification(w -> {
                                    Marshaller<Object, byte[]> marshaller = extractMarshaller(execution);
                                    ClientComputeJobPacker.packJobResult(val, marshaller, w);
                                    packJobState(w, state);
                                }, err)));
            }
        }).thenCompose(Function.identity());
    }

    static void packSubmitResult(ClientMessagePacker out, UUID jobId, ClusterNode node) {
        out.packUuid(jobId);
        packClusterNode(node, out);
    }

    private static <T> @Nullable Marshaller<T, byte[]> extractMarshaller(JobExecution<ComputeJobDataHolder> e) {
        if (e instanceof MarshallerProvider) {
            return ((MarshallerProvider<T>) e).resultMarshaller();
        }

        return null;
    }
}
