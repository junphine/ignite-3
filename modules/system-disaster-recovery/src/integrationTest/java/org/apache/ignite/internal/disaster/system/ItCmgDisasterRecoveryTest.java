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

package org.apache.ignite.internal.disaster.system;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.TestWrappers.unwrapIgniteImpl;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureExceptionMatcher.willTimeoutIn;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.apache.ignite.Ignite;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.internal.ClusterPerTestIntegrationTest;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.app.IgniteServerImpl;
import org.apache.ignite.internal.cluster.management.ClusterState;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologySnapshot;
import org.apache.ignite.table.KeyValueView;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ItCmgDisasterRecoveryTest extends ClusterPerTestIntegrationTest {
    @Override
    protected int initialNodes() {
        return 0;
    }

    @Test
    void repairWhenCmgWas1Node() throws Exception {
        // Node with index 2 will host neither of voting sets.
        startAndInitCluster(3, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        UUID originalClusterId = clusterState(igniteImpl(0)).clusterTag().clusterId();

        // This makes the CMG majority go away.
        cluster.stopNode(0);

        IgniteImpl igniteImpl1BeforeRestart = igniteImpl(1);

        assertThatCmgHasNoMajority(igniteImpl1BeforeRestart);

        initiateCmgRepairVia(igniteImpl1BeforeRestart, 1);

        IgniteImpl restartedIgniteImpl1 = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(restartedIgniteImpl1);

        ClusterState newClusterState = clusterState(restartedIgniteImpl1);
        assertThat(newClusterState.clusterTag().clusterId(), is(not(originalClusterId)));
        assertThat(newClusterState.formerClusterIds(), contains(originalClusterId));

        assertResetClusterMessageIsNotPresentAt(restartedIgniteImpl1);
        assertResetClusterMessageIsNotPresentAt(waitTillNodeRestartsInternally(2));
    }

    private void startAndInitCluster(int nodeCount, int[] cmgNodeIndexes, int[] metastorageNodeIndexes) {
        // Pre-allocate this to make sure that for each pair of nodes, if they start almost at the same time, at least one is able to make
        // an initial sync to another one.
        cluster.overrideSeedsCount(10);

        cluster.startAndInit(nodeCount, paramsBuilder -> {
            paramsBuilder.cmgNodeNames(nodeNames(cmgNodeIndexes));
            paramsBuilder.metaStorageNodeNames(nodeNames(metastorageNodeIndexes));
        });
    }

    private void waitTillClusterStateIsSavedToVaultOnConductor(int nodeIndex) throws InterruptedException {
        assertTrue(waitForCondition(
                () -> new SystemDisasterRecoveryStorage(igniteImpl(nodeIndex).vault()).readClusterState() != null,
                SECONDS.toMillis(10)
        ));
    }

    private static void assertThatCmgHasNoMajority(IgniteImpl igniteImpl1BeforeRestart) {
        assertThat(igniteImpl1BeforeRestart.logicalTopologyService().logicalTopologyOnLeader(), willTimeoutIn(1, SECONDS));
    }

    private static void waitTillCmgHasMajority(IgniteImpl restartedIgniteImpl1) {
        assertThat(restartedIgniteImpl1.logicalTopologyService().logicalTopologyOnLeader(), willCompleteSuccessfully());
    }

    private void initiateCmgRepairVia(IgniteImpl conductor, int... newCmgIndexes) {
        // TODO: IGNITE-22812 - initiate repair via CLI.

        CompletableFuture<Void> initiationFuture = conductor.systemDisasterRecoveryManager()
                .resetCluster(List.of(nodeNames(newCmgIndexes)));
        assertThat(initiationFuture, willCompleteSuccessfully());
    }

    private String[] nodeNames(int... nodeIndexes) {
        return IntStream.of(nodeIndexes)
                .mapToObj(cluster::nodeName)
                .toArray(String[]::new);
    }

    private IgniteImpl waitTillNodeRestartsInternally(int nodeIndex) throws InterruptedException {
        // restartOrShutdownFuture() becomes non-null when restart or shutdown is initiated; we know it's restart.

        assertTrue(
                waitForCondition(() -> restartOrShutdownFuture(nodeIndex) != null, SECONDS.toMillis(20)),
                "Node did not attempt to be restarted (or shut down) in time"
        );
        assertThat(restartOrShutdownFuture(nodeIndex), willCompleteSuccessfully());

        return unwrapIgniteImpl(cluster.server(nodeIndex).api());
    }

    @Nullable
    private CompletableFuture<Void> restartOrShutdownFuture(int nodeIndex) {
        return ((IgniteServerImpl) cluster.server(nodeIndex)).restartOrShutdownFuture();
    }

    private static ClusterState clusterState(IgniteImpl restartedIgniteImpl1)
            throws InterruptedException, ExecutionException, TimeoutException {
        return restartedIgniteImpl1.clusterManagementGroupManager().clusterState().get(10, SECONDS);
    }

    private static void assertResetClusterMessageIsNotPresentAt(IgniteImpl ignite) {
        assertThat(new SystemDisasterRecoveryStorage(ignite.vault()).readResetClusterMessage(), is(nullValue()));
    }

    @Test
    void repairWhenCmgWas3Nodes() throws Exception {
        // Node with index 5 will host neither of voting sets.
        startAndInitCluster(6, new int[]{0, 1, 2}, new int[]{2, 3, 4});
        waitTillClusterStateIsSavedToVaultOnConductor(2);

        // Stop the majority of CMG.
        IntStream.of(0, 1).parallel().forEach(cluster::stopNode);

        IgniteImpl igniteImpl2BeforeRestart = igniteImpl(2);

        assertThatCmgHasNoMajority(igniteImpl2BeforeRestart);

        initiateCmgRepairVia(igniteImpl2BeforeRestart, 2, 3, 4);

        IgniteImpl restartedIgniteImpl2 = waitTillNodeRestartsInternally(2);
        waitTillCmgHasMajority(restartedIgniteImpl2);

        // TODO: IGNITE-23096 - remove after the hang is fixed.
        waitTillNodesRestartInProcess(3, 4, 5);
    }

    private void waitTillNodesRestartInProcess(int... nodeIndexes) throws InterruptedException {
        for (int i : nodeIndexes) {
            waitTillNodeRestartsInternally(i);
        }
    }

    @Test
    void repairedClusterCanJoinBlankNodes() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        cluster.stopNode(0);

        initiateCmgRepairVia(igniteImpl(1), 1);

        // Doing this wait to make sure that blank node will be able to connect at least someone. If we don't do this, the new node
        // will still be able to connect, but this will happen on Scalecube's initial sync retry, and we don't want to wait for it
        // in our test.
        waitTillNodeRestartsInternally(1);

        IgniteImpl node2 = unwrapIgniteImpl(cluster.startNode(2));

        assertTrue(waitForCondition(
                () -> node2.logicalTopologyService().localLogicalTopology().nodes().stream()
                        .anyMatch(n -> node2.name().equals(n.name())),
                SECONDS.toMillis(10)
        ));
    }

    @Test
    void repairIsPossibleWhenAllNodesWaitForCmgMajorityOnJoin() throws Exception {
        // Node with index 2 will host neither of voting sets.
        startAndInitCluster(3, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        cluster.stopNode(0);

        IntStream.of(1, 2).parallel().forEach(this::restartPartially);

        initiateCmgRepairVia(((IgniteServerImpl) cluster.server(1)).igniteImpl(), 1);

        IgniteImpl restartedIgniteImpl1 = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(restartedIgniteImpl1);

        // TODO: IGNITE-23096 - remove after the hang is fixed.
        waitTillNodeRestartsInternally(2);
    }

    private void restartPartially(int index) {
        cluster.stopNode(index);
        cluster.startEmbeddedNode(index);
    }

    @Test
    void nodesThatSawNoReparationHaveSeparatePhysicalTopologies() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        // This makes the CMG majority go away.
        cluster.stopNode(0);

        initiateCmgRepairVia(igniteImpl(1), 1);

        IgniteImpl restartedIgniteImpl1 = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(restartedIgniteImpl1);

        // Starting the node that did not see the repair.
        cluster.startEmbeddedNode(0);

        assertFalse(
                waitForCondition(() -> restartedIgniteImpl1.clusterNodes().size() > 1, SECONDS.toMillis(3)),
                "Nodes from different clusters were able to establish a connection"
        );
    }

    @Test
    void migratesNodesThatSawNoReparationToNewCluster() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        breakAndRepairCmgMajorityInTwoNodeCluster();

        LogicalTopologySnapshot topologySnapshot = igniteImpl(1).logicalTopologyService().logicalTopologyOnLeader().get(10, SECONDS);
        assertTopologyContainsNode(0, topologySnapshot);
    }

    private void assertTopologyContainsNode(int nodeIndex, LogicalTopologySnapshot topologySnapshot) {
        assertTrue(topologySnapshot.nodes().stream().anyMatch(node -> node.name().equals(cluster.nodeName(nodeIndex))));
    }

    private void migrate(int oldClusterNodeIndex, int newClusterNodeIndex) throws Exception {
        // Starting the node that did not see the repair.
        IgniteImpl nodeMissingRepair = ((IgniteServerImpl) cluster.startEmbeddedNode(oldClusterNodeIndex).server()).igniteImpl();

        initiateMigrationToNewCluster(nodeMissingRepair, igniteImpl(newClusterNodeIndex));

        waitTillNodeRestartsInternally(oldClusterNodeIndex);
    }

    private static void initiateMigrationToNewCluster(IgniteImpl nodeMissingRepair, IgniteImpl repairedNode) throws Exception {
        // TODO: IGNITE-22879 - initiate migration via CLI.

        ClusterState newClusterState = clusterState(repairedNode);

        CompletableFuture<Void> migrationFuture = nodeMissingRepair.systemDisasterRecoveryManager().migrate(newClusterState);
        assertThat(migrationFuture, willCompleteSuccessfully());
    }

    @Test
    void migratesManyNodesThatSawNoReparationToNewCluster() throws Exception {
        startAndInitCluster(5, new int[]{0, 1, 2}, new int[]{2, 3, 4});
        waitTillClusterStateIsSavedToVaultOnConductor(2);

        // Stop the majority of CMG.
        IntStream.of(0, 1).parallel().forEach(cluster::stopNode);

        // Repair CMG with nodes 2, 3, 4.
        initiateCmgRepairVia(igniteImpl(2), 2, 3, 4);
        IgniteImpl restartedIgniteImpl2 = waitTillNodeRestartsInternally(2);
        waitTillCmgHasMajority(restartedIgniteImpl2);

        // Starting the nodes that did not see the repair (in parallel, to save time).
        List<IgniteImpl> partialNodes = IntStream.of(0, 1).parallel()
                .mapToObj(index -> ((IgniteServerImpl) cluster.startEmbeddedNode(index).server()).igniteImpl())
                .collect(toList());

        initiateMigrationToNewCluster(partialNodes.get(0), igniteImpl(2));

        waitTillNodeRestartsInternally(0);
        waitTillNodeRestartsInternally(1);

        LogicalTopologySnapshot topologySnapshot = igniteImpl(2).logicalTopologyService().logicalTopologyOnLeader().get(10, SECONDS);
        assertTopologyContainsNode(0, topologySnapshot);
        assertTopologyContainsNode(1, topologySnapshot);

        // TODO: IGNITE-23096 - remove after the hang is fixed.
        waitTillNodesRestartInProcess(3, 4);
    }

    @Test
    void repeatedRepairWorks() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        breakAndRepairCmgMajorityInTwoNodeCluster();

        // Second repair.
        initiateCmgRepairVia(igniteImpl(1), 1);
        IgniteImpl igniteImpl1RestartedSecondTime = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(igniteImpl1RestartedSecondTime);

        // TODO: IGNITE-23096 - remove after the hang is fixed.
        waitTillNodesRestartInProcess(0, 1);
    }

    @Test
    void embeddedApiKeepsWorkingAfterCmgRepair() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        Ignite ignite1 = node(1);
        ignite1.sql().executeScript("CREATE TABLE TEST (ID INT PRIMARY KEY, VAL VARCHAR)");
        KeyValueView<Integer, String> kvView = ignite1.tables().table("TEST").keyValueView(Integer.class, String.class);

        kvView.put(null, 1, "one");

        breakAndRepairCmgMajorityInTwoNodeCluster();

        assertThat(kvView.get(null, 1), is("one"));
    }

    @Test
    void clientKeepsWorkingAfterCmgRepair() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        try (IgniteClient client = clientAgainstNode0()) {
            client.sql().executeScript("CREATE TABLE TEST (ID INT PRIMARY KEY, VAL VARCHAR)");
            KeyValueView<Integer, String> kvView = client.tables().table("TEST").keyValueView(Integer.class, String.class);

            kvView.put(null, 1, "one");

            breakAndRepairCmgMajorityInTwoNodeCluster();

            assertThat(kvView.get(null, 1), is("one"));
        }
    }

    private void breakAndRepairCmgMajorityInTwoNodeCluster() throws Exception {
        // This makes the CMG majority go away.
        cluster.stopNode(0);

        // Repair CMG with just node 1.
        initiateCmgRepairVia(igniteImpl(1), 1);
        IgniteImpl restartedIgniteImpl1 = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(restartedIgniteImpl1);

        // Starting the node that did not see the repair.
        migrate(0, 1);
    }

    private IgniteClient clientAgainstNode0() {
        return IgniteClient.builder()
                .addresses(igniteImpl(0).clientAddress().host())
                .build();
    }

    @Test
    void dataNodesAreUpdatedCorrectlyAfterClusterReset() throws Exception {
        startAndInitCluster(2, new int[]{0}, new int[]{1});
        waitTillClusterStateIsSavedToVaultOnConductor(1);

        final String zoneName = "TEST_ZONE";

        cluster.node(1).sql().execute(
                null,
                "CREATE ZONE " + zoneName + " WITH STORAGE_PROFILES='default', "
                        + "DATA_NODES_AUTO_ADJUST_SCALE_UP=0, DATA_NODES_AUTO_ADJUST_SCALE_DOWN=0"
        );

        int zoneId = igniteImpl(1).catalogManager().zone("TEST_ZONE", Long.MAX_VALUE).id();

        waitTillDataNodesBecome(new int[]{0, 1}, zoneId, igniteImpl(1));

        cluster.startNode(2);

        waitTillDataNodesBecome(new int[]{0, 1, 2}, zoneId, igniteImpl(1));

        // This makes the CMG majority go away.
        cluster.stopNode(0);

        // Now, dataNodes should have become [1, 2], but as there is no CMG leader, no one is able to trigger data nodes update.

        // Repair CMG with just node 1.
        initiateCmgRepairVia(igniteImpl(1), 1);
        IgniteImpl restartedIgniteImpl1 = waitTillNodeRestartsInternally(1);
        waitTillCmgHasMajority(restartedIgniteImpl1);

        waitTillDataNodesBecome(new int[]{1, 2}, zoneId, restartedIgniteImpl1);

        // Starting the node that did not see the repair.
        migrate(0, 1);

        waitTillDataNodesBecome(new int[]{0, 1, 2}, zoneId, restartedIgniteImpl1);

        // Now let's make sure that normal additions/removals still work after cluster reset.

        cluster.stopNode(0);
        waitTillDataNodesBecome(new int[]{1, 2}, zoneId, restartedIgniteImpl1);

        cluster.startNode(3);
        waitTillDataNodesBecome(new int[]{1, 2, 3}, zoneId, restartedIgniteImpl1);

        // TODO: IGNITE-23096 - remove after the hang is fixed.
        waitTillNodesRestartInProcess(1, 2);
    }

    private void waitTillDataNodesBecome(int[] expectedDataNodeIndexes, int zoneId, IgniteImpl ignite) throws InterruptedException {
        int catalogVersion = ignite.catalogManager().latestCatalogVersion();

        assertTrue(
                waitForCondition(
                        () -> currentDataNodes(ignite, catalogVersion, zoneId).equals(Set.of(nodeNames(expectedDataNodeIndexes))),
                        SECONDS.toMillis(10)
                ),
                "Did not see data nodes to become " + IntStream.of(expectedDataNodeIndexes).boxed().collect(toList()) + " in time"
        );
    }

    private static Set<String> currentDataNodes(IgniteImpl ignite, int catalogVersion, int zoneId) {
        long currentRevision = ignite.metaStorageManager().appliedRevision();

        CompletableFuture<Set<String>> dataNodesFuture = ignite.distributionZoneManager()
                .dataNodes(currentRevision, catalogVersion, zoneId);

        assertThat(dataNodesFuture, willCompleteSuccessfully());
        return dataNodesFuture.join();
    }
}
