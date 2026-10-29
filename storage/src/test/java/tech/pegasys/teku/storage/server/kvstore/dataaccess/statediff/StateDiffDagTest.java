/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;
import org.junit.jupiter.api.Test;
import tech.pegasys.infrastructure.logging.LogCaptor;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.MissingSupernodeException;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.interop.MockStartValidatorKeyPairFactory;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.gloas.BeaconStateSchemaGloas;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.EpochProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.SlotProcessingException;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.MockKvStoreInstance;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.CombinedKvStoreDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedStateSnapshotStorageLogic;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffMetrics.WritePhase;
import tech.pegasys.teku.storage.server.kvstore.schema.V6SchemaCombinedSnapshot;

class StateDiffDagTest {

  private static final int VALIDATOR_COUNT = 32;
  private static final Comparator<Bytes32> BY_HEX = Comparator.comparing(Bytes32::toHexString);

  private Spec spec = TestSpecFactory.createMinimalElectra();
  private DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private KvStoreCombinedDao dao = createDao(spec);
  private final StubMetricsSystem metricsSystem = new StubMetricsSystem();
  private StateDiffDag dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), metricsSystem);

  private final List<BeaconState> states = new ArrayList<>();
  private final List<Bytes32> roots = new ArrayList<>();

  @Test
  void writesSnapshotForFirstNode() {
    buildChain(1);

    writeEpochs(0, 0);

    final StateDiffNodeRecord record = record(roots.get(0));
    assertThat(record.snapshot()).isTrue();
    assertThat(record.parentNodeRoot()).isEqualTo(Bytes32.ZERO);
    assertThat(record.blockSlot()).isEqualTo(states.get(0).getSlot());
    assertThat(record.stateRoot()).isEqualTo(states.get(0).hashTreeRoot());
    assertThat(record.firstEpoch()).isEqualTo(UInt64.ZERO);
    assertThat(record.lastEpoch()).isEqualTo(UInt64.ZERO);
    assertThat(record.level()).isZero();
    assertReconstructs(0);
    assertThat(nodeCount("written")).isEqualTo(1);
  }

  @Test
  void writesDiffAgainstPreviousEpoch() {
    buildChain(2);

    writeEpochs(0, 1);

    final StateDiffNodeRecord record = record(roots.get(1));
    assertThat(record.snapshot()).isFalse();
    assertThat(record.parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(record.level()).isEqualTo(3);
    assertThat(record.payload().size()).isLessThan(record(roots.get(0)).payload().size());
    assertReconstructs(1);
    assertThat(dag.reconstructionChain(dao, roots.get(1)))
        .containsExactly(roots.get(1), roots.get(0));
  }

  @Test
  void reconstructsEveryNodeInChainOf40Epochs() {
    buildChain(40);

    writeEpochs(0, 39);

    for (int epoch = 0; epoch < 40; epoch++) {
      assertReconstructs(epoch);
    }
    assertThat(record(roots.get(0)).level()).isZero();
    assertThat(record(roots.get(16)).level()).isEqualTo(2);
    assertThat(record(roots.get(32)).level()).isEqualTo(2);
    assertThat(record(roots.get(31)).level()).isEqualTo(3);
    // A level 16 node diffs against the previous level 16 (or coarser) node
    assertThat(record(roots.get(32)).parentNodeRoot()).isEqualTo(roots.get(16));
    assertThat(record(roots.get(16)).parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(record(roots.get(33)).parentNodeRoot()).isEqualTo(roots.get(32));

    final List<Bytes32> chain = dag.reconstructionChain(dao, roots.get(39));
    assertThat(chain).hasSizeLessThanOrEqualTo(1 + 3 + 7);
    assertThat(chain)
        .containsExactly(
            roots.get(39),
            roots.get(38),
            roots.get(37),
            roots.get(36),
            roots.get(35),
            roots.get(34),
            roots.get(33),
            roots.get(32),
            roots.get(16),
            roots.get(0));
    // Every diff base came from the per-level cache
    assertThat(baseCache(metricsSystem, "hit")).isEqualTo(39);
    assertThat(baseCache(metricsSystem, "miss")).isZero();
    assertDagConsistent();
  }

  @Test
  void reconstructsParentsAfterRestart() {
    buildChain(43);
    writeEpochs(0, 40);

    final StubMetricsSystem restartedMetrics = new StubMetricsSystem();
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), restartedMetrics);
    writeEpochs(41, 42);

    assertThat(record(roots.get(41)).parentNodeRoot()).isEqualTo(roots.get(40));
    assertReconstructs(41);
    assertReconstructs(42);
    assertThat(baseCache(restartedMetrics, "miss")).isEqualTo(1);
    assertThat(baseCache(restartedMetrics, "hit")).isEqualTo(1);
  }

  @Test
  void writesTwoBranchesAtCoarseBoundaryIndependently() {
    buildChain(17);
    writeEpochs(0, 15);

    final Bytes32 rootA16 = roots.get(16);
    final BeaconState stateA16 = states.get(16);
    final Bytes32 rootB16 = dataStructureUtil.randomBytes32();
    final BeaconState stateB16 =
        stateA16.updated(
            state -> state.getBalances().setElement(0, UInt64.valueOf(31_000_000_000L)));
    assertThat(stateB16.hashTreeRoot()).isNotEqualTo(stateA16.hashTreeRoot());

    final Bytes32 rootA17 = dataStructureUtil.randomBytes32();
    final BeaconState stateA17 = processToEpoch(stateA16, 17);
    final Bytes32 rootB17 = dataStructureUtil.randomBytes32();
    final BeaconState stateB17 = processToEpoch(stateB16, 17);

    final List<Bytes32> ancestorsA = ancestorsBefore(16);
    // Branch B forked before epoch 15's checkpoint block
    final List<Bytes32> ancestorsB = ancestorsBefore(15);
    update(
        tx -> {
          tx.writeNode(rootA16, stateA16, epoch(16), epoch(16), ancestorsA, false);
          tx.writeNode(rootB16, stateB16, epoch(16), epoch(16), ancestorsB, false);
        });
    update(
        tx ->
            tx.writeNode(
                rootA17, stateA17, epoch(17), epoch(17), prepend(rootA16, ancestorsA), false));
    update(
        tx ->
            tx.writeNode(
                rootB17, stateB17, epoch(17), epoch(17), prepend(rootB16, ancestorsB), false));
    assertThat(record(rootA16).parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(record(rootB16).parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(record(rootA17).parentNodeRoot()).isEqualTo(rootA16);
    assertThat(record(rootB17).parentNodeRoot()).isEqualTo(rootB16);
    assertReconstructs(rootA16, stateA16);
    assertReconstructs(rootB16, stateB16);
    assertReconstructs(rootA17, stateA17);
    assertReconstructs(rootB17, stateB17);
  }

  @Test
  void extendsServedEpochsAcrossEmptyEpochs() {
    buildChain(16);
    writeEpochs(0, 15);
    final Bytes32 root15 = roots.get(15);
    final StateDiffNodeRecord original = record(root15);

    update(
        tx ->
            tx.writeNode(root15, states.get(15), epoch(15), epoch(17), ancestorsBefore(15), false));

    final StateDiffNodeRecord extended = record(root15);
    assertThat(extended.firstEpoch()).isEqualTo(epoch(15));
    assertThat(extended.lastEpoch()).isEqualTo(epoch(17));
    assertThat(extended)
        .isEqualTo(
            StateDiffNodeRecordCodec.decode(
                StateDiffNodeRecordCodec.patchLastEpoch(
                    StateDiffNodeRecordCodec.encode(original), epoch(17))));

    // A smaller last epoch never lowers the stored one
    update(
        tx ->
            tx.writeNode(root15, states.get(15), epoch(15), epoch(16), ancestorsBefore(15), false));
    assertThat(record(root15).lastEpoch()).isEqualTo(epoch(17));

    // The next checkpoint block, after the empty epochs 16 and 17, is served at epoch 18
    final Bytes32 root18 = dataStructureUtil.randomBytes32();
    final BeaconState state18 = processToEpoch(states.get(15), 18);
    update(tx -> tx.writeNode(root18, state18, epoch(18), epoch(18), ancestorsBefore(16), false));

    final StateDiffNodeRecord record18 = record(root18);
    assertThat(record18.parentNodeRoot()).isEqualTo(root15);
    assertThat(record18.level()).isEqualTo(3);
    assertReconstructs(root18, state18);
    assertReconstructs(15);
  }

  @Test
  void forcesSnapshotAtMilestoneChange() {
    spec = TestSpecFactory.createMinimalWithFuluForkEpoch(UInt64.valueOf(4));
    dataStructureUtil = new DataStructureUtil(spec);
    dao = createDao(spec);
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), metricsSystem);
    buildChain(6);

    writeEpochs(0, 5);

    assertThat(spec.atSlot(states.get(3).getSlot()).getMilestone())
        .isEqualTo(SpecMilestone.ELECTRA);
    assertThat(spec.atSlot(states.get(4).getSlot()).getMilestone()).isEqualTo(SpecMilestone.FULU);
    assertThat(record(roots.get(3)).snapshot()).isFalse();
    final StateDiffNodeRecord forkNode = record(roots.get(4));
    assertThat(forkNode.snapshot()).isTrue();
    assertThat(forkNode.parentNodeRoot()).isEqualTo(Bytes32.ZERO);
    assertThat(record(roots.get(5)).parentNodeRoot()).isEqualTo(roots.get(4));
    for (int epoch = 0; epoch < 6; epoch++) {
      assertReconstructs(epoch);
    }
  }

  @Test
  void verificationFailureThrows() {
    buildChain(2);
    writeEpochs(0, 1);
    final StateDiffNodeRecord record = record(roots.get(1));
    final byte[] corruptedRoot = record.stateRoot().toArrayUnsafe().clone();
    corruptedRoot[0] ^= 1;
    final StateDiffNodeRecord corrupted =
        new StateDiffNodeRecord(
            record.blockSlot(),
            Bytes32.wrap(corruptedRoot),
            record.firstEpoch(),
            record.lastEpoch(),
            record.level(),
            record.snapshot(),
            record.parentNodeRoot(),
            record.payload());
    updateDao(
        updater ->
            updater.putStateDiffNode(roots.get(1), StateDiffNodeRecordCodec.encode(corrupted)));

    assertThatThrownBy(() -> dag.reconstruct(dao, roots.get(1)))
        .isInstanceOf(StateDiffVerificationException.class)
        .hasMessageContaining(roots.get(1).toHexString())
        .hasMessageContaining(roots.get(0).toHexString());
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "state_diff_verification_failures_total"))
        .isEqualTo(1);
  }

  @Test
  void commitWritesSupernodesWithRefCounts() {
    buildChain(1);
    final Bytes32 rootA = dataStructureUtil.randomBytes32();
    final Bytes32 rootB = dataStructureUtil.randomBytes32();

    // Both nodes are written in one transaction, so the counts are accumulated in memory
    update(
        tx -> {
          tx.writeNode(rootA, states.get(0), epoch(0), epoch(0), List.of(), false);
          tx.writeNode(rootB, states.get(0), epoch(0), epoch(0), List.of(rootA), true);
        });

    final Set<Bytes32> hashes = supernodeHashes(rootA);
    assertThat(hashes).isNotEmpty();
    assertThat(supernodeHashes(rootB)).isEqualTo(hashes);
    for (final Bytes32 hash : hashes) {
      assertThat(dao.getStateSupernodeRefCount(hash)).contains(UInt64.valueOf(2));
      assertThat(dao.getStateSupernode(hash)).isPresent();
    }
    assertThat(supernodeOps("inserted")).isEqualTo(hashes.size());
    assertDagConsistent();
  }

  @Test
  void missingSupernodeFailsReconstruction() {
    buildChain(1);
    writeEpochs(0, 0);
    final Bytes32 hash = supernodeHashes(roots.get(0)).iterator().next();

    updateDao(updater -> updater.deleteStateSupernode(hash));

    assertThatThrownBy(() -> dag.reconstruct(dao, roots.get(0)))
        .isInstanceOf(StateDiffVerificationException.class)
        .hasMessageContaining(hash.toHexString())
        .hasMessageContaining(roots.get(0).toHexString())
        .hasCauseInstanceOf(MissingSupernodeException.class);
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "state_diff_verification_failures_total"))
        .isEqualTo(1);
  }

  @Test
  void deleteNodesReleasesSupernodes() {
    buildChain(2);
    writeEpochs(0, 1);
    final Set<Bytes32> hashes = supernodeHashes(roots.get(0));

    update(
        tx ->
            tx.deleteNodes(List.of(roots.get(1), roots.get(0), dataStructureUtil.randomBytes32())));

    assertThat(dao.getStateDiffNode(roots.get(0))).isEmpty();
    assertThat(dao.getStateDiffNode(roots.get(1))).isEmpty();
    for (final Bytes32 hash : hashes) {
      assertThat(dao.getStateSupernode(hash)).isEmpty();
      assertThat(dao.getStateSupernodeRefCount(hash)).isEmpty();
    }
    assertThat(nodeCount("deleted")).isEqualTo(2);
    assertThat(supernodeOps("deleted")).isEqualTo(supernodeOps("inserted"));
    assertDagConsistent();
  }

  @Test
  void deleteNodesToleratesCorruptPayload() {
    buildChain(3);
    writeEpochs(0, 2);
    final Set<Bytes32> corruptHashes = supernodeHashes(roots.get(1));
    final Set<Bytes32> releasedHashes = supernodeHashes(roots.get(2));
    final StateDiffNodeRecord record = record(roots.get(1));
    // A pruned fork node whose payload cannot be decoded
    updateDao(
        updater ->
            updater.putStateDiffNode(
                roots.get(1),
                StateDiffNodeRecordCodec.encode(
                    new StateDiffNodeRecord(
                        record.blockSlot(),
                        record.stateRoot(),
                        record.firstEpoch(),
                        record.lastEpoch(),
                        record.level(),
                        record.snapshot(),
                        record.parentNodeRoot(),
                        Bytes.of(9, 9, 9)))));
    final Map<Bytes32, Optional<UInt64>> countsBefore = new HashMap<>();
    corruptHashes.forEach(hash -> countsBefore.put(hash, dao.getStateSupernodeRefCount(hash)));
    final long deletedBefore = nodeCount("deleted");

    update(tx -> tx.deleteNodes(List.of(roots.get(1), roots.get(2))));

    assertThat(dao.getStateDiffNode(roots.get(1))).isEmpty();
    assertThat(dao.getStateDiffNode(roots.get(2))).isEmpty();
    assertThat(nodeCount("deleted_corrupt")).isEqualTo(1);
    assertThat(nodeCount("deleted")).isEqualTo(deletedBefore + 1);
    // The corrupt node keeps its supernode references: a leak, never a missing supernode
    final Set<Bytes32> onlyCorrupt = new HashSet<>(corruptHashes);
    onlyCorrupt.removeAll(releasedHashes);
    for (final Bytes32 hash : onlyCorrupt) {
      assertThat(dao.getStateSupernodeRefCount(hash)).isEqualTo(countsBefore.get(hash));
      assertThat(dao.getStateSupernode(hash)).isPresent();
    }
    assertReconstructs(0);
  }

  @Test
  void keepsSupernodeReferencedByRetainedNode() {
    buildChain(1);
    final Bytes32 rootA = dataStructureUtil.randomBytes32();
    final Bytes32 rootB = dataStructureUtil.randomBytes32();
    update(tx -> tx.writeNode(rootA, states.get(0), epoch(0), epoch(0), List.of(), false));
    update(tx -> tx.writeNode(rootB, states.get(0), epoch(0), epoch(0), List.of(rootA), true));
    final Set<Bytes32> hashes = supernodeHashes(rootA);

    update(tx -> tx.deleteNodes(List.of(rootA)));

    assertThat(dao.getStateDiffNode(rootA)).isEmpty();
    for (final Bytes32 hash : hashes) {
      assertThat(dao.getStateSupernodeRefCount(hash)).contains(UInt64.ONE);
      assertThat(dao.getStateSupernode(hash)).isPresent();
    }
    assertReconstructs(rootB, states.get(0));
    assertDagConsistent();
  }

  @Test
  void retainFinalizedChainKeepsOnlyAnchorChain() {
    buildChain(40);
    writeEpochs(0, 39);

    // The first anchor has no previous anchor to enumerate from
    retain(Optional.empty(), 5);
    assertStoredEpochs(range(0, 39));

    retain(Optional.of(roots.get(5)), 20);
    final Set<Integer> afterAnchor20 = range(21, 39);
    afterAnchor20.addAll(Set.of(0, 16, 17, 18, 19, 20));
    assertStoredEpochs(afterAnchor20);

    retain(Optional.of(roots.get(20)), 39);
    final Set<Integer> afterAnchor39 = range(32, 39);
    afterAnchor39.addAll(Set.of(0, 16));
    assertStoredEpochs(afterAnchor39);

    for (final int epoch : afterAnchor39) {
      assertReconstructs(epoch);
    }
  }

  @Test
  void retainFinalizedChainReadsEachNodeRecordOnce() {
    final V6SchemaCombinedSnapshot schema = V6SchemaCombinedSnapshot.createV6(spec);
    final KvStoreAccessor db =
        spy(MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables()));
    dao =
        new CombinedKvStoreDao<>(
            db, schema, new V4FinalizedStateSnapshotStorageLogic<>(1), new StubMetricsSystem());
    buildChain(21);
    writeEpochs(0, 20);
    retain(Optional.empty(), 5);
    clearInvocations(db);

    // Both anchor chains share the snapshot, and the previous chain's nodes are also deleted
    update(
        tx ->
            tx.retainFinalizedChain(
                Optional.of(roots.get(5)),
                roots.get(20),
                roots.subList(6, 21),
                FinalizedRetention.anchorChain()));

    final List<Object> nodeReads =
        mockingDetails(db).getInvocations().stream()
            .filter(
                invocation ->
                    invocation.getMethod().getName().equals("get")
                        && invocation.getArguments().length == 2
                        && invocation.getArgument(0).equals(schema.getColumnStateDiffNodes()))
            .map(invocation -> invocation.getArgument(1))
            .toList();
    assertThat(nodeReads).contains(roots.get(0), roots.get(5), roots.get(20));
    assertThat(nodeReads).doesNotHaveDuplicates();
    assertStoredEpochs(union(Set.of(0), range(16, 20)));
  }

  @Test
  void retainFinalizedChainDropsOldSnapshotAfterNewOne() {
    dag = new StateDiffDag(spec, new StateDiffDagConfig(true, List.of(4L, 2L, 1L)), metricsSystem);
    buildChain(10);
    writeEpochs(0, 5);
    // A forced snapshot at epoch 6, as written for a new anchor
    update(
        tx ->
            tx.writeNode(
                roots.get(6), states.get(6), epoch(6), epoch(6), ancestorsBefore(6), true));
    writeEpochs(7, 9);
    assertThat(record(roots.get(6)).snapshot()).isTrue();
    // The snapshot is a valid base for every level; epoch 8, the coarsest level, is a snapshot
    assertThat(record(roots.get(7)).parentNodeRoot()).isEqualTo(roots.get(6));
    assertThat(record(roots.get(8)).snapshot()).isTrue();

    retain(Optional.of(roots.get(0)), 3);
    assertStoredEpochs(Set.of(0, 2, 3, 4, 5, 6, 7, 8, 9));

    retain(Optional.of(roots.get(3)), 7);

    assertStoredEpochs(Set.of(6, 7, 8, 9));
    assertThat(dao.getStateDiffNode(roots.get(0))).isEmpty();
    for (int epoch = 6; epoch < 10; epoch++) {
      assertReconstructs(epoch);
    }
  }

  @Test
  void retainFinalizedChainDropsNodesWhenAnchorNodeWasCreatedLate() {
    buildChain(11);
    writeEpochs(0, 9);
    retain(Optional.empty(), 5);
    assertStoredEpochs(range(0, 9));

    // The node of epoch 10 was skipped; finalization creates it against the previous anchor, so
    // its parent, its previous node, skips the nodes of epochs 6..9
    update(
        tx -> {
          tx.ensureNode(roots.get(10), states.get(10), epoch(10), List.of(roots.get(5)));
          tx.retainFinalizedChain(
              Optional.of(roots.get(5)),
              roots.get(10),
              roots.subList(6, 11),
              FinalizedRetention.anchorChain());
        });
    assertStoredEpochs(union(range(0, 5), Set.of(10)));
    assertThat(dag.reconstructionChain(dao, roots.get(10)))
        .containsExactly(
            roots.get(10),
            roots.get(5),
            roots.get(4),
            roots.get(3),
            roots.get(2),
            roots.get(1),
            roots.get(0));
    assertReconstructs(10);
    assertDagConsistent();
  }

  @Test
  void retainFinalizedChainKeepsParentOfHotNodeWhenAnchorNodeWasCreatedLate() {
    buildChain(7);
    writeEpochs(0, 4);
    retain(Optional.empty(), 2);
    // The node of epoch 5 was skipped, so the node of epoch 6 diffs against epoch 4
    update(
        tx ->
            tx.writeNode(
                roots.get(6), states.get(6), epoch(6), epoch(6), ancestorsBefore(6), false));
    assertThat(record(roots.get(6)).parentNodeRoot()).isEqualTo(roots.get(4));

    // Finalizing epoch 5 creates its node from its finalized ancestors, down to the previous anchor
    update(
        tx -> {
          tx.ensureNode(
              roots.get(5),
              states.get(5),
              epoch(5),
              List.of(roots.get(4), roots.get(3), roots.get(2)));
          tx.retainFinalizedChain(
              Optional.of(roots.get(2)),
              roots.get(5),
              roots.subList(3, 6),
              FinalizedRetention.anchorChain());
        });

    assertThat(record(roots.get(5)).parentNodeRoot()).isEqualTo(roots.get(4));
    assertStoredEpochs(range(0, 6));
    assertReconstructs(6);
    assertDagConsistent();

    // The next anchor move drops the node of epoch 5 alone
    retain(Optional.of(roots.get(5)), 6);
    assertStoredEpochs(Set.of(0, 1, 2, 3, 4, 6));
    assertReconstructs(6);
  }

  @Test
  void retainFinalizedChainKeepsParentOfHotNodeWhenCoarseAnchorNodeWasCreatedLate() {
    buildChain(19);
    writeEpochs(0, 15);
    retain(Optional.empty(), 14);
    // The node of epoch 16 was skipped, so the node of epoch 17 diffs against epoch 15
    update(
        tx ->
            tx.writeNode(
                roots.get(17), states.get(17), epoch(17), epoch(17), ancestorsBefore(17), false));
    assertThat(record(roots.get(17)).parentNodeRoot()).isEqualTo(roots.get(15));

    // Finalizing epoch 16, a coarse epoch, creates its node late. At level 16 its parent would be
    // the genesis snapshot and retention would delete node 15, the parent of node 17
    update(
        tx -> {
          tx.ensureNode(roots.get(16), states.get(16), epoch(16), ancestorsBefore(16));
          tx.retainFinalizedChain(
              Optional.of(roots.get(15)),
              roots.get(16),
              List.of(roots.get(15), roots.get(16)),
              FinalizedRetention.anchorChain());
        });

    final StateDiffNodeRecord late = record(roots.get(16));
    assertThat(late.snapshot()).isFalse();
    assertThat(late.level()).isEqualTo(3);
    assertThat(late.parentNodeRoot()).isEqualTo(roots.get(15));
    assertReconstructs(16);
    assertReconstructs(17);
    assertDagConsistent();

    // The next two finalizations load every chain they need
    retain(Optional.of(roots.get(16)), 17);
    assertStoredEpochs(union(range(0, 15), Set.of(17)));
    assertReconstructs(17);
    writeEpochs(18, 18);
    assertThat(record(roots.get(18)).parentNodeRoot()).isEqualTo(roots.get(17));
    retain(Optional.of(roots.get(17)), 18);
    assertStoredEpochs(union(range(0, 15), Set.of(17, 18)));
    assertReconstructs(18);
  }

  @Test
  void anchorWhoseChainCannotBeLoadedIsRewrittenAsSnapshotAndDropsReadablePreviousChain() {
    buildChain(10);
    writeEpochs(0, 9);
    retain(Optional.empty(), 5);
    // Node 3 is lost: the chains of nodes 4 and later are broken
    updateDao(updater -> updater.deleteStateDiffNode(roots.get(3)));
    assertThatThrownBy(() -> dag.reconstruct(dao, roots.get(7)))
        .isInstanceOf(StateDiffVerificationException.class);

    try (final LogCaptor logCaptor = LogCaptor.forClass(StateDiffDagTransaction.class)) {
      update(
          tx ->
              tx.onAnchorFinalized(
                  Optional.of(roots.get(5)),
                  roots.get(7),
                  states.get(7),
                  epoch(7),
                  List.of(roots.get(6), roots.get(5)),
                  List.of(roots.get(6), roots.get(7)),
                  FinalizedRetention.anchorChain()));

      assertThat(logCaptor.getErrorLogs())
          .singleElement()
          .satisfies(
              log ->
                  assertThat(log)
                      .contains(roots.get(7).toHexString())
                      .contains("its chain or the previous anchor's cannot be loaded"));
    }

    assertThat(nodeCount("anchor_repaired")).isEqualTo(1);
    assertThat(nodeCount("anchor_created_late")).isZero();
    final StateDiffNodeRecord repaired = record(roots.get(7));
    assertThat(repaired.snapshot()).isTrue();
    assertThat(repaired.firstEpoch()).isEqualTo(epoch(7));
    assertThat(repaired.lastEpoch()).isEqualTo(epoch(7));
    assertThat(snapshots("forced")).isEqualTo(1);
    // The readable part of the previous anchor's chain and the newly finalized nodes are gone; the
    // nodes below the lost one cannot be found from the previous anchor any more
    assertStoredEpochs(Set.of(0, 1, 2, 7, 8, 9));
    assertReconstructs(7);
    // Nodes above the anchor that diff against it work again
    assertReconstructs(8);
    assertReconstructs(9);
  }

  @Test
  void corruptRecordFailsWithVerificationException() {
    buildChain(2);
    writeEpochs(0, 1);
    updateDao(updater -> updater.putStateDiffNode(roots.get(1), Bytes.of(2, 2, 3)));

    assertThatThrownBy(() -> dag.reconstruct(dao, roots.get(1)))
        .isInstanceOf(StateDiffVerificationException.class)
        .hasMessageContaining(roots.get(1).toHexString())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "state_diff_verification_failures_total"))
        .isEqualTo(1);
  }

  @Test
  void corruptPayloadFailsWithVerificationException() {
    buildChain(2);
    writeEpochs(0, 1);
    final StateDiffNodeRecord record = record(roots.get(1));
    final StateDiffNodeRecord corrupted =
        new StateDiffNodeRecord(
            record.blockSlot(),
            record.stateRoot(),
            record.firstEpoch(),
            record.lastEpoch(),
            record.level(),
            record.snapshot(),
            record.parentNodeRoot(),
            Bytes.of(9, 9, 9));
    updateDao(
        updater ->
            updater.putStateDiffNode(roots.get(1), StateDiffNodeRecordCodec.encode(corrupted)));

    assertThatThrownBy(() -> dag.reconstruct(dao, roots.get(1)))
        .isInstanceOf(StateDiffVerificationException.class)
        .hasMessageContaining(roots.get(1).toHexString())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "state_diff_verification_failures_total"))
        .isEqualTo(1);
  }

  @Test
  void reconstructChainFoldsDeltasOnce() {
    assertFoldedReconstructionMatchesPerLinkReference();
  }

  @Test
  void reconstructChainFoldsDeltasOnceWithProgressiveLists() {
    useSpec(TestSpecFactory.createMinimalGloas());
    assertThat(dag.support().schemaFor(UInt64.ZERO)).isInstanceOf(BeaconStateSchemaGloas.class);

    assertFoldedReconstructionMatchesPerLinkReference();
  }

  private void assertFoldedReconstructionMatchesPerLinkReference() {
    buildChain(10);
    writeEpochs(0, 9);
    final List<Bytes32> chain = dag.reconstructionChain(dao, roots.get(9));
    assertThat(chain).hasSize(10);

    // The per-link reference: every diff applied in full, building its delta fields each time
    final BeaconStateSchema<?, ?> schema = dag.support().schemaFor(states.get(9).getSlot());
    final TreeDiffApplier applier = new TreeDiffApplier(schema);
    TreeNode perLink = schema.getDefaultTree();
    int perLinkListBuilds = 0;
    final Set<Integer> deltaFields = new HashSet<>();
    for (final Bytes32 root : chain.reversed()) {
      final TreeDiff diff = TreeDiffCodec.deserialize(record(root).payload());
      perLinkListBuilds += diff.deltas().size();
      diff.deltas().forEach(delta -> deltaFields.add(delta.fieldIndex()));
      perLink = applier.apply(perLink, diff, dao::getStateSupernode);
    }
    final long listBuildsBefore = listBuilds();

    final BeaconState folded = uncachedDag().reconstruct(dao, roots.get(9)).orElseThrow();

    assertThat(folded.hashTreeRoot()).isEqualTo(perLink.hashTreeRoot());
    assertThat(folded.sszSerialize())
        .isEqualTo(schema.createFromBackingNode(perLink).sszSerialize());
    assertThat(folded).isEqualTo(states.get(9));
    // Balances change every epoch, so the per-link path builds them once per link
    assertThat(perLinkListBuilds).isGreaterThanOrEqualTo(chain.size());
    assertThat(listBuilds() - listBuildsBefore).isEqualTo(deltaFields.size());
  }

  @Test
  void recordsWritePhases() {
    buildChain(2);

    writeEpochs(0, 1);

    final StateDiffMetrics metrics = dag.metrics();
    assertThat(metrics.writePhaseSeconds(WritePhase.BASE)).isNotNegative();
    assertThat(metrics.writePhaseSeconds(WritePhase.WALK)).isPositive();
    assertThat(metrics.writePhaseSeconds(WritePhase.ENCODE)).isPositive();
    assertThat(metrics.writePhaseSeconds(WritePhase.STORE)).isPositive();
  }

  @Test
  void anchorWrittenAndRepairedInOneTransactionIsRecordedOnce() {
    buildChain(3);
    writeEpochs(0, 1);
    final long sectionEntriesBefore = sectionEntries("leaves") + sectionEntries("deltas");
    // The snapshot below the previous anchor is lost. The anchor node of epoch 2 can still be
    // created against the cached node 1, but the previous anchor's chain no longer loads
    updateDao(updater -> updater.deleteStateDiffNode(roots.get(0)));

    // The anchor of epoch 2 is created late, then rewritten by a repair in the same transaction
    update(
        tx ->
            tx.onAnchorFinalized(
                Optional.of(roots.get(1)),
                roots.get(2),
                states.get(2),
                epoch(2),
                ancestorsBefore(2),
                List.of(roots.get(2)),
                FinalizedRetention.anchorChain()));

    assertThat(nodeCount("anchor_created_late")).isEqualTo(1);
    assertThat(nodeCount("anchor_repaired")).isEqualTo(1);
    assertThat(record(roots.get(2)).snapshot()).isTrue();
    assertThat(nodeCount("written")).isEqualTo(3);
    assertThat(snapshots("forced")).isEqualTo(1);
    final TreeDiff repaired = TreeDiffCodec.deserialize(record(roots.get(2)).payload());
    assertThat(sectionEntries("leaves") + sectionEntries("deltas") - sectionEntriesBefore)
        .isEqualTo(repaired.leaves().size() + repaired.deltas().size());
    assertReconstructs(2);
  }

  @Test
  void anchorNodeThatCannotBeCreatedIsWrittenAsSnapshot() {
    buildChain(3);
    writeEpochs(0, 1);
    // Node 0 is lost and, after a restart, node 1, the diff base of a late anchor node of epoch 2,
    // is not cached: it cannot be reconstructed
    updateDao(updater -> updater.deleteStateDiffNode(roots.get(0)));
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), metricsSystem);

    try (final LogCaptor logCaptor = LogCaptor.forClass(StateDiffDagTransaction.class)) {
      update(
          tx ->
              tx.onAnchorFinalized(
                  Optional.of(roots.get(1)),
                  roots.get(2),
                  states.get(2),
                  epoch(2),
                  ancestorsBefore(2),
                  List.of(roots.get(2)),
                  FinalizedRetention.anchorChain()));

      assertThat(logCaptor.getErrorLogs())
          .singleElement()
          .satisfies(
              log ->
                  assertThat(log)
                      .contains(roots.get(2).toHexString())
                      .contains("its missing node cannot be created"));
    }

    assertThat(nodeCount("anchor_created_late")).isZero();
    assertThat(nodeCount("anchor_repaired")).isEqualTo(1);
    final StateDiffNodeRecord repaired = record(roots.get(2));
    assertThat(repaired.snapshot()).isTrue();
    assertThat(repaired.firstEpoch()).isEqualTo(epoch(2));
    assertThat(repaired.lastEpoch()).isEqualTo(epoch(2));
    // The readable prefix of the previous anchor's chain is dropped
    assertStoredEpochs(Set.of(2));
    assertReconstructs(2);
  }

  @Test
  void retainFinalizedChainDeletesCorruptCandidateWithoutRepair() {
    buildChain(22);
    writeEpochs(0, 21);
    retain(Optional.empty(), 5);
    // Node 10 is finalized by the move to anchor 20 and is not in the chain of anchor 20
    final StateDiffNodeRecord record = record(roots.get(10));
    updateDao(
        updater ->
            updater.putStateDiffNode(
                roots.get(10),
                StateDiffNodeRecordCodec.encode(
                    new StateDiffNodeRecord(
                        record.blockSlot(),
                        record.stateRoot(),
                        record.firstEpoch(),
                        record.lastEpoch(),
                        record.level(),
                        record.snapshot(),
                        record.parentNodeRoot(),
                        Bytes.of(9, 9, 9)))));
    final long deletedBefore = nodeCount("deleted");

    update(
        tx ->
            tx.retainFinalizedChain(
                Optional.of(roots.get(5)),
                roots.get(20),
                roots.subList(6, 21),
                FinalizedRetention.anchorChain()));

    assertThat(nodeCount("deleted_corrupt")).isEqualTo(1);
    // Nodes 1..5 of the previous chain and 6..15 but the corrupt one
    assertThat(nodeCount("deleted")).isEqualTo(deletedBefore + 14);
    assertStoredEpochs(Set.of(0, 16, 17, 18, 19, 20, 21));
    assertThat(record(roots.get(20)).snapshot()).isFalse();
    assertReconstructs(20);
    assertReconstructs(21);
  }

  @Test
  void writesPeriodicSnapshotAtCoarsestLevel() {
    dag = new StateDiffDag(spec, new StateDiffDagConfig(true, List.of(4L, 2L, 1L)), metricsSystem);
    buildChain(21);

    writeEpochs(0, 20);

    for (final int epoch : List.of(4, 8, 12, 16, 20)) {
      final StateDiffNodeRecord record = record(roots.get(epoch));
      assertThat(record.snapshot()).describedAs("snapshot at epoch %s", epoch).isTrue();
      assertThat(record.level()).isZero();
      assertThat(record.parentNodeRoot()).isEqualTo(Bytes32.ZERO);
    }
    // The first node, at epoch 0, is a periodic snapshot too
    assertThat(snapshots("periodic")).isEqualTo(6);
    assertThat(snapshots("no_parent") + snapshots("forced") + snapshots("milestone")).isZero();
    for (int epoch = 0; epoch <= 20; epoch++) {
      // A snapshot, then at most one node per finer level: 1 + (2 - 1) + (2 - 1)
      assertThat(dag.reconstructionChain(dao, roots.get(epoch)))
          .describedAs("reconstruction chain of epoch %s", epoch)
          .hasSizeLessThanOrEqualTo(3);
      assertReconstructs(epoch);
    }

    retain(Optional.empty(), 5);
    assertStoredEpochs(range(4, 20));
    // Once the anchor passes the next periodic snapshot, the whole older chain goes in one step
    retain(Optional.of(roots.get(5)), 9);
    assertStoredEpochs(range(8, 20));
    retain(Optional.of(roots.get(9)), 13);
    assertStoredEpochs(range(12, 20));
    for (int epoch = 12; epoch <= 20; epoch++) {
      assertThat(dag.reconstructionChain(dao, roots.get(epoch))).hasSizeLessThanOrEqualTo(3);
      assertReconstructs(epoch);
    }
  }

  @Test
  void siblingNodesForTheSameEpochShareTheCachedParent() {
    buildChain(17);
    writeEpochs(0, 15);
    final long hitsBefore = baseCache(metricsSystem, "hit");
    final long missesBefore = baseCache(metricsSystem, "miss");
    final Bytes32 rootA16 = roots.get(16);
    final BeaconState stateA16 = states.get(16);
    // Another checkpoint block for epoch 16 with the same parent, as after a start-slot reorg
    final Bytes32 rootB16 = dataStructureUtil.randomBytes32();
    final BeaconState stateB16 =
        stateA16.updated(
            state -> state.getBalances().setElement(0, UInt64.valueOf(31_000_000_000L)));

    update(tx -> tx.writeNode(rootA16, stateA16, epoch(16), epoch(16), ancestorsBefore(16), false));
    update(tx -> tx.writeNode(rootB16, stateB16, epoch(16), epoch(16), ancestorsBefore(16), false));

    assertThat(record(rootA16).parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(record(rootB16).parentNodeRoot()).isEqualTo(roots.get(0));
    assertThat(baseCache(metricsSystem, "hit")).isEqualTo(hitsBefore + 2);
    assertThat(baseCache(metricsSystem, "miss")).isEqualTo(missesBefore);
    assertReconstructs(rootA16, stateA16);
    assertReconstructs(rootB16, stateB16);
  }

  @Test
  void reconstructionStartsFromTheNearestCachedNodeOfTheChain() {
    buildChain(10);
    writeEpochs(0, 9);
    final Map<String, List<Double>> histograms = new HashMap<>();
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), recordingMetrics(histograms));
    final BeaconState fullChain = dag.reconstruct(dao, roots.get(9)).orElseThrow();
    assertThat(dag.reconstructionChain(dao, roots.get(9))).hasSize(10);

    // Node 5 is cached: reconstructing node 9 applies only the four nodes above it
    assertThat(dag.reconstructAndCache(dao, roots.get(5))).contains(states.get(5));
    final BeaconState fromCachedNode = dag.reconstruct(dao, roots.get(9)).orElseThrow();

    assertThat(fromCachedNode.hashTreeRoot()).isEqualTo(fullChain.hashTreeRoot());
    // Balances change every epoch, so the folded delta fields start from node 5's values
    assertThat(fromCachedNode).isEqualTo(states.get(9));
    assertThat(histograms.get("state_diff_reconstruct_chain_length"))
        .containsExactly(10.0, 6.0, 4.0);
  }

  @Test
  void reconstructionAppliesTheNodeItselfEvenWhenItsStateIsCached() {
    buildChain(2);
    writeEpochs(0, 1);
    final Map<String, List<Double>> histograms = new HashMap<>();
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), recordingMetrics(histograms));
    assertThat(dag.reconstructAndCache(dao, roots.get(1))).isPresent();

    assertReconstructs(1);
    // Starts from the snapshot, as node 1 is the head and node 0 is not cached
    assertThat(histograms.get("state_diff_reconstruct_chain_length")).containsExactly(2.0, 2.0);
  }

  @Test
  void firstWriteAfterRestartDiffsAgainstTheCachedAnchor() {
    buildChain(12);
    writeEpochs(0, 10);
    final StubMetricsSystem restartedMetrics = new StubMetricsSystem();
    final Map<String, List<Double>> histograms = new HashMap<>();
    dag =
        new StateDiffDag(
            spec, StateDiffDagConfig.prune(), recordingMetrics(restartedMetrics, histograms));
    // The finalized anchor, reconstructed when the store is loaded
    assertThat(dag.reconstructAndCache(dao, roots.get(10))).isPresent();
    final int reconstructions = histograms.get("state_diff_reconstruct_chain_length").size();

    writeEpochs(11, 11);

    assertThat(record(roots.get(11)).parentNodeRoot()).isEqualTo(roots.get(10));
    assertThat(baseCache(restartedMetrics, "hit")).isEqualTo(1);
    assertThat(baseCache(restartedMetrics, "miss")).isZero();
    assertThat(histograms.get("state_diff_reconstruct_chain_length")).hasSize(reconstructions);
    assertReconstructs(11);
  }

  @Test
  void missedDiffBaseIsReconstructedFromTheNearestCachedNodeOfItsChain() {
    buildChain(14);
    writeEpochs(0, 12);
    final StubMetricsSystem restartedMetrics = new StubMetricsSystem();
    final Map<String, List<Double>> histograms = new HashMap<>();
    dag =
        new StateDiffDag(
            spec, StateDiffDagConfig.prune(), recordingMetrics(restartedMetrics, histograms));
    assertThat(dag.reconstructAndCache(dao, roots.get(8))).isPresent();

    // The parent, node 12, is not cached: nodes 9 to 12 are applied to the cached node 8
    writeEpochs(13, 13);

    assertThat(record(roots.get(13)).parentNodeRoot()).isEqualTo(roots.get(12));
    assertThat(baseCache(restartedMetrics, "miss")).isEqualTo(1);
    assertThat(histograms.get("state_diff_reconstruct_chain_length")).containsExactly(9.0, 4.0);
    assertReconstructs(13);
  }

  @Test
  void failedTransactionDoesNotCountOrCacheItsNodes() {
    buildChain(2);
    writeEpochs(0, 0);
    final long written = nodeCount("written");
    final long periodic = snapshots("periodic");
    final long leafEntries = sectionEntries("leaves");

    try (final HotUpdater updater = dao.hotUpdater()) {
      final StateDiffDagTransaction transaction = dag.begin(dao, updater);
      transaction.writeNode(
          roots.get(1), states.get(1), epoch(1), epoch(1), ancestorsBefore(1), false);
      // The transaction fails before it is committed
      assertThatThrownBy(
              () ->
                  transaction.retainFinalizedChain(
                      Optional.empty(),
                      dataStructureUtil.randomBytes32(),
                      List.of(),
                      FinalizedRetention.anchorChain()))
          .isInstanceOf(IllegalStateException.class);
    }

    assertThat(dao.getStateDiffNode(roots.get(1))).isEmpty();
    assertThat(nodeCount("written")).isEqualTo(written);
    assertThat(snapshots("periodic")).isEqualTo(periodic);
    assertThat(sectionEntries("leaves")).isEqualTo(leafEntries);
    assertThat(dag.baseCache().find(roots.get(1))).isEmpty();
  }

  @Test
  void countsPayloadSectionsOfWrittenNodes() {
    buildChain(2);

    writeEpochs(0, 1);

    final List<StateDiffNodeRecord> nodes = List.of(record(roots.get(0)), record(roots.get(1)));
    final List<TreeDiff> diffs =
        nodes.stream().map(node -> TreeDiffCodec.deserialize(node.payload())).toList();
    final List<TreeDiffCodec.SectionBytes> spans = nodes.stream().map(this::sectionBytes).toList();
    assertThat(sectionEntries("supernodes"))
        .isEqualTo(diffs.stream().mapToLong(diff -> diff.supernodes().size()).sum())
        .isPositive();
    assertThat(sectionBytes("supernodes"))
        .isEqualTo(spans.stream().mapToLong(TreeDiffCodec.SectionBytes::supernodes).sum());
    assertThat(sectionEntries("leaves")).isPositive();
    assertThat(sectionBytes("leaves"))
        .isEqualTo(spans.stream().mapToLong(TreeDiffCodec.SectionBytes::leaves).sum());
    // Balances and inactivity scores are delta sections, counted raw and compressed as stored
    assertThat(sectionEntries("deltas")).isPositive();
    assertThat(sectionEntries("deltas_compressed")).isEqualTo(sectionEntries("deltas"));
    assertThat(sectionBytes("deltas"))
        .isEqualTo(
            diffs.stream()
                .flatMap(diff -> diff.deltas().stream())
                .mapToLong(delta -> delta.encodedDeltas().length)
                .sum());
    assertThat(sectionBytes("deltas_compressed"))
        .isEqualTo(spans.stream().mapToLong(TreeDiffCodec.SectionBytes::deltas).sum())
        .isPositive()
        .isLessThan(sectionBytes("deltas"));
    // The sections as stored fill the payloads but for their version bytes
    assertThat(
            sectionBytes("leaves")
                + sectionBytes("supernodes")
                + sectionBytes("patches")
                + sectionBytes("zeroed")
                + sectionBytes("copied")
                + sectionBytes("deltas_compressed"))
        .isEqualTo(nodes.stream().mapToLong(node -> node.payload().size() - 1).sum());
  }

  @Test
  void changedValidatorIsPatchedInlineWithoutStoringOrCountingASupernode() {
    buildChain(2);
    writeEpochs(0, 0);
    final Set<Bytes32> storedHashes = supernodeHashes(roots.get(0));
    final Map<Bytes32, Optional<UInt64>> countsBefore = new HashMap<>();
    storedHashes.forEach(hash -> countsBefore.put(hash, dao.getStateSupernodeRefCount(hash)));
    final long insertedBefore = supernodeOps("inserted");
    final BeaconState changed = withChangedValidator(states.get(1));
    final Bytes32 changedRoot = roots.get(1);

    update(tx -> tx.writeNode(changedRoot, changed, epoch(1), epoch(1), ancestorsBefore(1), false));

    final StateDiffNodeRecord record = record(changedRoot);
    assertThat(record.snapshot()).isFalse();
    final TreeDiff diff = TreeDiffCodec.deserialize(record.payload());
    assertThat(diff.supernodes()).isEmpty();
    assertThat(diff.patches())
        .singleElement()
        .satisfies(patch -> assertThat(patch.elementIndices().toIntArray()).containsExactly(0));
    // Nothing stored or counted for the patched supernode
    assertThat(supernodeOps("inserted")).isEqualTo(insertedBefore);
    storedHashes.forEach(
        hash -> assertThat(dao.getStateSupernodeRefCount(hash)).isEqualTo(countsBefore.get(hash)));
    assertThat(sectionEntries("patches")).isEqualTo(1);
    // The patch section of the changed node, and the empty one of the snapshot below it
    assertThat(sectionBytes("patches"))
        .isEqualTo(sectionBytes(record).patches() + sectionBytes(record(roots.get(0))).patches())
        .isGreaterThan(diff.patches().getFirst().elements().size());
    assertReconstructs(changedRoot, changed);
    setFinalizedCheckpoint(0);
    assertDagConsistent();

    // Deleting the node releases no reference
    update(tx -> tx.deleteNodes(List.of(changedRoot)));
    storedHashes.forEach(
        hash -> assertThat(dao.getStateSupernodeRefCount(hash)).isEqualTo(countsBefore.get(hash)));
    assertDagConsistent();
  }

  @Test
  void resolvesCoarseParentAfterRetention() {
    buildChain(65);
    writeEpochs(0, 39);
    retain(Optional.empty(), 39);
    assertStoredEpochs(union(Set.of(0, 16), range(32, 39)));

    writeEpochs(40, 48);

    assertThat(record(roots.get(48)).snapshot()).isFalse();
    assertThat(record(roots.get(48)).parentNodeRoot()).isEqualTo(roots.get(32));

    // The walk from 63 must cross 47..33, whose predecessors 31..17 were deleted
    writeEpochs(49, 64);

    final StateDiffNodeRecord record64 = record(roots.get(64));
    assertThat(record64.level()).isEqualTo(1);
    assertThat(record64.snapshot()).isFalse();
    assertThat(record64.parentNodeRoot()).isEqualTo(roots.get(0));
    // Only the first node, at the coarsest level, is a snapshot
    assertThat(snapshots("periodic")).isEqualTo(1);
    assertThat(snapshots("no_parent") + snapshots("forced") + snapshots("milestone")).isZero();
    assertReconstructs(64);
    assertDagConsistent();
  }

  @Test
  void retainAllKeepsEverything() {
    buildChain(21);
    writeEpochs(0, 20);

    retain(Optional.of(roots.get(0)), 20, FinalizedRetention.all());

    assertStoredEpochs(range(0, 20));
  }

  @Test
  void windowKeepsTheWindowNodesAndTheChainOfTheOldestOne() {
    buildChain(40);
    writeEpochs(0, 39);

    // Node 20's chain runs through nodes 19..16 to the snapshot: nodes 1..15 left the window
    retainWindow(Optional.empty(), 20);
    assertStoredEpochs(union(Set.of(0), range(16, 39)));
    assertThat(dao.getStateDiffWindowStart()).contains(epoch(20));

    // Node 33's chain is 32, 16 and the snapshot: nodes 17..31 left, the previous chain with them
    retainWindow(Optional.of(20), 33);
    final Set<Integer> retained = union(Set.of(0, 16), range(32, 39));
    assertStoredEpochs(retained);
    assertThat(dao.getStateDiffWindowStart()).contains(epoch(33));
    assertThat(windowStartGauge()).isEqualTo(33);
    assertThat(nodeCount("deleted")).isEqualTo(30);
    for (final int epoch : retained) {
      assertReconstructs(epoch);
    }
  }

  @Test
  void windowKeepsTheSnapshotThatStartsTheNewOldestChain() {
    dag = new StateDiffDag(spec, new StateDiffDagConfig(true, List.of(4L, 2L, 1L)), metricsSystem);
    buildChain(12);
    writeEpochs(0, 11);
    // Epochs 0, 4 and 8, the coarsest level, are snapshots
    assertThat(record(roots.get(4)).snapshot()).isTrue();
    assertThat(record(roots.get(8)).snapshot()).isTrue();

    retainWindow(Optional.empty(), 3);
    assertStoredEpochs(union(Set.of(0, 2), range(3, 11)));

    // Node 4 left the window range of the previous start, but it is the snapshot of node 5's chain
    retainWindow(Optional.of(3), 5);
    assertStoredEpochs(range(4, 11));

    retainWindow(Optional.of(5), 9);
    assertStoredEpochs(range(8, 11));
    for (int epoch = 8; epoch < 12; epoch++) {
      assertReconstructs(epoch);
    }
  }

  @Test
  void windowKeepsTheParentOfAHotNodeWhenItsOldestNodeWasCreatedLate() {
    buildChain(19);
    writeEpochs(0, 15);
    // Node 14's chain is every node down to the snapshot: nothing leaves
    retainWindow(Optional.empty(), 14);
    assertStoredEpochs(range(0, 15));
    // The node of epoch 16 was skipped, so the node of epoch 17 diffs against epoch 15
    update(
        tx ->
            tx.writeNode(
                roots.get(17), states.get(17), epoch(17), epoch(17), ancestorsBefore(17), false));

    // Finalizing epoch 16 with the window starting there creates node 16 late, at the finest
    // level: its chain contains node 15, the parent of node 17
    update(
        tx ->
            tx.onAnchorFinalized(
                Optional.of(roots.get(15)),
                roots.get(16),
                states.get(16),
                epoch(16),
                ancestorsBefore(16),
                List.of(roots.get(16)),
                FinalizedRetention.window(
                    epoch(16), Optional.of(roots.get(14)), roots.subList(14, 17))));

    assertThat(nodeCount("anchor_created_late")).isEqualTo(1);
    assertThat(record(roots.get(16)).parentNodeRoot()).isEqualTo(roots.get(15));
    assertStoredEpochs(range(0, 17));
    assertThat(dao.getStateDiffWindowStart()).contains(epoch(16));
    assertReconstructs(16);
    assertReconstructs(17);
    assertDagConsistent();

    // The next window move drops node 16 alone
    retainWindow(Optional.of(16), 17);
    assertStoredEpochs(union(range(0, 15), Set.of(17)));
    assertReconstructs(17);
  }

  @Test
  void windowStartsAtTheLatestNodeBeforeASkippedCheckpointNode() {
    buildChain(22);
    writeEpochs(0, 19);
    // The node of epoch 20 was skipped
    writeEpochs(21, 21);

    retainWindow(Optional.empty(), 20);

    // The oldest retained node is node 19, whose chain runs through 16 to the snapshot
    assertStoredEpochs(union(Set.of(0), Set.of(16, 17, 18, 19, 21)));
    assertThat(dao.getStateDiffWindowStart()).contains(epoch(20));
    assertReconstructs(21);
  }

  @Test
  void windowWithoutAnyNodeKeepsEverything() {
    buildChain(4);
    writeEpochs(0, 3);

    update(
        tx ->
            tx.retainFinalizedChain(
                Optional.empty(),
                roots.get(3),
                List.of(),
                FinalizedRetention.window(
                    epoch(3),
                    Optional.of(roots.get(0)),
                    List.of(
                        dataStructureUtil.randomBytes32(), dataStructureUtil.randomBytes32()))));

    assertStoredEpochs(range(0, 3));
    assertThat(dao.getStateDiffWindowStart()).isEmpty();
  }

  @Test
  void windowWhoseOldestChainCannotBeLoadedKeepsEverythingAndItsStart() {
    buildChain(21);
    writeEpochs(0, 20);
    retainWindow(Optional.empty(), 5);
    // Node 17 is lost: node 20's chain cannot be loaded
    updateDao(updater -> updater.deleteStateDiffNode(roots.get(17)));

    try (final LogCaptor logCaptor = LogCaptor.forClass(StateDiffDagTransaction.class)) {
      update(
          tx ->
              tx.retainFinalizedChain(
                  Optional.empty(),
                  roots.get(20),
                  List.of(),
                  FinalizedRetention.window(
                      epoch(20), Optional.of(roots.get(5)), roots.subList(5, 21))));

      assertThat(logCaptor.getErrorLogs())
          .singleElement()
          .asString()
          .contains("oldest retained state diff node " + roots.get(20));
    }
    assertStoredEpochs(union(range(0, 16), range(18, 20)));
    assertThat(dao.getStateDiffWindowStart()).contains(epoch(5));
  }

  @Test
  void ensureNodeWritesOnlyWhenAbsent() {
    buildChain(2);
    writeEpochs(0, 0);
    final StateDiffNodeRecord original = record(roots.get(0));

    update(
        tx -> {
          assertThat(tx.ensureNode(roots.get(0), states.get(0), epoch(3), List.of())).isFalse();
          assertThat(tx.ensureNode(roots.get(1), states.get(1), epoch(1), List.of(roots.get(0))))
              .isTrue();
        });

    assertThat(record(roots.get(0))).isEqualTo(original);
    assertThat(record(roots.get(1)).parentNodeRoot()).isEqualTo(roots.get(0));
    assertReconstructs(1);
  }

  @Test
  void commitRejectsReleasingMissingCount() {
    buildChain(1);
    writeEpochs(0, 0);
    final Bytes32 hash = supernodeHashes(roots.get(0)).iterator().next();
    updateDao(updater -> updater.deleteStateSupernode(hash));

    try (final HotUpdater updater = dao.hotUpdater()) {
      final StateDiffDagTransaction transaction = dag.begin(dao, updater);
      transaction.deleteNodes(List.of(roots.get(0)));
      assertThatThrownBy(transaction::commit)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(hash.toHexString());
    }
    assertThat(dao.getStateDiffNode(roots.get(0))).isPresent();
  }

  @Test
  void reconstructReturnsEmptyForUnknownRoot() {
    assertThat(dag.reconstruct(dao, dataStructureUtil.randomBytes32())).isEmpty();
    assertThat(dag.reconstructionChain(dao, dataStructureUtil.randomBytes32())).isEmpty();
  }

  @Test
  void verifyReportsConsistentDag() {
    buildChain(3);
    writeEpochs(0, 2);
    setFinalizedCheckpoint(2);

    final StateDiffVerificationReport report = dag.verify(dao);

    assertThat(report.nodeCount()).isEqualTo(3);
    assertThat(report.supernodeCount()).isEqualTo(allSupernodeHashes().size()).isPositive();
    assertThat(report.refCountMismatches()).isEmpty();
    assertThat(report.missingSupernodes()).isEmpty();
    assertThat(report.unreferencedSupernodes()).isEmpty();
    assertThat(report.supernodesHashChecked()).isEqualTo(report.supernodeCount());
    assertThat(report.supernodeHashMismatches()).isEmpty();
    assertThat(report.nodesWithMissingParent()).isEmpty();
    assertThat(report.anchorRoot()).contains(roots.get(2));
    assertThat(report.anchorChainLength()).isEqualTo(3);
    assertThat(report.anchorReconstructionOk()).isTrue();
    assertThat(report.anchorReconstructionMillis()).isNotNegative();
    assertThat(report.anchorReconstructionError()).isEmpty();
    assertThat(report.hasProblems()).isFalse();
  }

  @Test
  void verifyReportsRefCountMismatch() {
    buildChain(2);
    writeEpochs(0, 1);
    setFinalizedCheckpoint(1);
    final Bytes32 hash = supernodeHashes(roots.get(0)).iterator().next();
    final UInt64 count = dao.getStateSupernodeRefCount(hash).orElseThrow();

    updateDao(updater -> updater.setStateSupernodeRefCount(hash, count.plus(3)));

    final StateDiffVerificationReport report = dag.verify(dao);
    assertThat(report.refCountMismatches())
        .containsExactly(
            new StateDiffVerificationReport.RefCountMismatch(
                hash, count.longValue(), Optional.of(count.plus(3))));
    assertThat(report.missingSupernodes()).isEmpty();
    // Reconstruction does not depend on counts
    assertThat(report.anchorReconstructionOk()).isTrue();
    assertThat(report.hasProblems()).isTrue();
  }

  @Test
  void verifyReportsMissingAndUnreferencedSupernodes() {
    buildChain(1);
    writeEpochs(0, 0);
    setFinalizedCheckpoint(0);
    final Bytes32 hash = supernodeHashes(roots.get(0)).iterator().next();
    final Bytes ssz = dao.getStateSupernode(hash).orElseThrow();
    final Bytes32 orphanHash = dataStructureUtil.randomBytes32();

    updateDao(
        updater -> {
          updater.deleteStateSupernode(hash);
          updater.putStateSupernode(orphanHash, ssz);
        });

    final StateDiffVerificationReport report = dag.verify(dao);
    // Deleting the supernode removed its count too
    assertThat(report.refCountMismatches())
        .containsExactly(
            new StateDiffVerificationReport.RefCountMismatch(hash, 1, Optional.empty()));
    assertThat(report.missingSupernodes()).containsExactly(hash);
    assertThat(report.unreferencedSupernodes()).containsExactly(orphanHash);
    // Neither the missing nor the unreferenced supernode can be hashed
    assertThat(report.supernodesHashChecked()).isEqualTo(report.supernodeCount() - 1);
    assertThat(report.supernodeHashMismatches()).isEmpty();
    assertThat(report.anchorReconstructionOk()).isFalse();
    assertThat(report.anchorReconstructionError())
        .hasValueSatisfying(error -> assertThat(error).contains(hash.toHexString()));
    assertThat(report.hasProblems()).isTrue();
  }

  @Test
  void verifyReportsSupernodesWhoseContentDoesNotHashToTheirKey() {
    buildChain(1);
    writeEpochs(0, 0);
    setFinalizedCheckpoint(0);
    final Bytes32 changedRoot = dataStructureUtil.randomBytes32();
    update(
        tx ->
            tx.writeNode(
                changedRoot,
                withChangedValidator(states.get(0)),
                epoch(0),
                epoch(0),
                List.of(),
                false));
    final Set<Bytes32> allHashes = new HashSet<>(supernodeHashes(roots.get(0)));
    allHashes.addAll(supernodeHashes(changedRoot));
    final List<Bytes32> hashes = allHashes.stream().sorted(BY_HEX).toList();
    assertThat(hashes).hasSizeGreaterThan(1);
    final Bytes32 flippedHash = hashes.get(0);
    final Bytes32 truncatedHash = hashes.get(1);
    final Bytes flipped = dao.getStateSupernode(flippedHash).orElseThrow();
    final Bytes truncated = dao.getStateSupernode(truncatedHash).orElseThrow();

    updateDao(
        updater -> {
          updater.putStateSupernode(
              flippedHash, Bytes.concatenate(Bytes.of((byte) ~flipped.get(0)), flipped.slice(1)));
          updater.putStateSupernode(truncatedHash, truncated.slice(0, truncated.size() - 1));
        });

    final StateDiffVerificationReport report = dag.verify(dao);
    assertThat(report.supernodesHashChecked()).isEqualTo(hashes.size());
    assertThat(report.supernodeHashMismatches()).containsExactly(flippedHash, truncatedHash);
    assertThat(report.refCountMismatches()).isEmpty();
    assertThat(report.hasProblems()).isTrue();
  }

  @Test
  void reconstructionTrustsSupernodeContentToMatchItsKey() {
    buildChain(1);
    writeEpochs(0, 0);
    final Bytes32 hash = supernodeHashes(roots.get(0)).iterator().next();
    final Bytes ssz = dao.getStateSupernode(hash).orElseThrow();

    updateDao(
        updater ->
            updater.putStateSupernode(
                hash, Bytes.concatenate(Bytes.of((byte) ~ssz.get(0)), ssz.slice(1))));

    // The state root check does not see the tampered content; verify does
    final BeaconState reconstructed = dag.reconstruct(dao, roots.get(0)).orElseThrow();
    assertThat(reconstructed.hashTreeRoot()).isEqualTo(states.get(0).hashTreeRoot());
    assertThat(reconstructed.sszSerialize()).isNotEqualTo(states.get(0).sszSerialize());
  }

  @Test
  void reconstructionLoadsTheSupernodesOfEachLinkInOneBatch() {
    dao = spy(createDao(spec));
    buildChain(3);
    writeEpochs(0, 2);
    final List<Bytes32> chain = dag.reconstructionChain(dao, roots.get(2));
    final long linksWithSupernodes =
        chain.stream().filter(root -> !supernodeHashes(root).isEmpty()).count();
    assertThat(linksWithSupernodes).isPositive();
    clearInvocations(dao);

    assertThat(uncachedDag().reconstruct(dao, roots.get(2)).orElseThrow()).isEqualTo(states.get(2));

    final List<String> supernodeReads =
        mockingDetails(dao).getInvocations().stream()
            .map(invocation -> invocation.getMethod().getName())
            .filter(name -> name.startsWith("getStateSupernode"))
            .toList();
    assertThat(supernodeReads)
        .containsOnly("getStateSupernodes")
        .hasSize((int) linksWithSupernodes);
  }

  @Test
  void transactionReaderServesPendingSupernodesBeforeCommittedOnes() {
    buildChain(1);
    writeEpochs(0, 0);
    final Set<Bytes32> committedHashes = supernodeHashes(roots.get(0));
    final Bytes32 committedHash = committedHashes.iterator().next();
    final Bytes32 missingHash = dataStructureUtil.randomBytes32();
    // A changed validator puts its supernode in the new node only
    final BeaconState changed = withChangedValidator(states.get(0));
    final Bytes32 changedRoot = dataStructureUtil.randomBytes32();

    try (final HotUpdater updater = dao.hotUpdater()) {
      final StateDiffDagTransaction transaction = dag.begin(dao, updater);
      transaction.writeNode(changedRoot, changed, epoch(0), epoch(0), List.of(), false);
      final Set<Bytes32> pendingHashes = supernodeHashes(transaction, changedRoot);
      pendingHashes.removeAll(committedHashes);
      assertThat(pendingHashes).isNotEmpty();
      pendingHashes.forEach(hash -> assertThat(dao.getStateSupernode(hash)).isEmpty());
      final List<Bytes32> found = new ArrayList<>(pendingHashes);
      found.add(committedHash);
      final List<Bytes32> requested = new ArrayList<>(found);
      requested.add(missingHash);

      final Map<Bytes32, Bytes> loaded = transaction.reader().loadAll(requested);

      assertThat(loaded.keySet()).containsExactlyInAnyOrderElementsOf(found);
      assertThat(loaded.get(committedHash))
          .isEqualTo(dao.getStateSupernode(committedHash).orElseThrow());
      assertThat(dag.reconstruct(transaction.reader(), changedRoot).orElseThrow())
          .isEqualTo(changed);
    }
  }

  @Test
  void appliersAreMemoizedPerMilestoneAcrossReaders() {
    buildChain(1);
    writeEpochs(0, 0);
    final TreeDiffApplier applier = dag.support().applierFor(states.get(0).getSlot());

    assertReconstructs(0);
    update(tx -> assertThat(dag.reconstruct(tx.reader(), roots.get(0))).isPresent());

    assertThat(dag.support().applierFor(states.get(0).getSlot())).isSameAs(applier);
  }

  @Test
  void verifyReportsNodeWithMissingParent() {
    buildChain(3);
    writeEpochs(0, 2);
    setFinalizedCheckpoint(2);

    // Bypasses the transaction, so the supernode references of the deleted node are not released
    updateDao(updater -> updater.deleteStateDiffNode(roots.get(1)));

    final StateDiffVerificationReport report = dag.verify(dao);
    assertThat(report.nodesWithMissingParent()).containsExactly(roots.get(2));
    assertThat(report.anchorReconstructionOk()).isFalse();
    assertThat(report.anchorChainLength()).isZero();
    assertThat(report.hasProblems()).isTrue();
  }

  @Test
  void verifyReportsCorruptNode() {
    buildChain(3);
    writeEpochs(0, 2);
    setFinalizedCheckpoint(2);

    // A record of the current version cut short in its header
    updateDao(updater -> updater.putStateDiffNode(roots.get(1), Bytes.of(2, 2, 3)));

    final StateDiffVerificationReport report = dag.verify(dao);
    assertThat(report.corruptNodes())
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.root()).isEqualTo(roots.get(1));
              assertThat(node.reason()).contains("shorter than its");
            });
    assertThat(report.nodesWithMissingParent()).isEmpty();
    assertThat(report.anchorReconstructionOk()).isFalse();
    assertThat(report.anchorReconstructionError())
        .hasValueSatisfying(error -> assertThat(error).contains(roots.get(1).toHexString()));
    assertThat(report.hasProblems()).isTrue();
  }

  @Test
  void verifyReportsMissingAnchorNode() {
    buildChain(1);
    writeEpochs(0, 0);
    final Bytes32 unknownRoot = dataStructureUtil.randomBytes32();
    updateDao(updater -> updater.setFinalizedCheckpoint(new Checkpoint(epoch(1), unknownRoot)));

    final StateDiffVerificationReport report = dag.verify(dao);

    assertThat(report.anchorRoot()).contains(unknownRoot);
    assertThat(report.anchorReconstructionOk()).isFalse();
    assertThat(report.anchorReconstructionError())
        .hasValueSatisfying(error -> assertThat(error).contains("No state diff node"));
    assertThat(report.hasProblems()).isTrue();
  }

  // Helpers

  private void useSpec(final Spec newSpec) {
    spec = newSpec;
    dataStructureUtil = new DataStructureUtil(spec);
    dao = createDao(spec);
    dag = new StateDiffDag(spec, StateDiffDagConfig.prune(), metricsSystem);
  }

  /**
   * Checks the stored nodes and supernodes with {@link StateDiffDag#verify}: reference counts,
   * supernode content and parent pointers. Its anchor reconstruction is not asserted: most tests
   * store no finalized checkpoint.
   */
  private void assertDagConsistent() {
    final StateDiffVerificationReport report = dag.verify(dao);
    assertThat(
            List.of(
                report.refCountMismatches(),
                report.missingSupernodes(),
                report.unreferencedSupernodes(),
                report.supernodeHashMismatches(),
                report.nodesWithMissingParent(),
                report.corruptNodes()))
        .describedAs("%s", report)
        .allSatisfy(problems -> assertThat(problems).isEmpty());
  }

  private void setFinalizedCheckpoint(final int epoch) {
    updateDao(
        updater -> updater.setFinalizedCheckpoint(new Checkpoint(epoch(epoch), roots.get(epoch))));
  }

  private Set<Bytes32> allSupernodeHashes() {
    final Set<Bytes32> hashes = new HashSet<>();
    roots.forEach(root -> hashes.addAll(supernodeHashes(root)));
    return hashes;
  }

  private static KvStoreCombinedDao createDao(final Spec spec) {
    final V6SchemaCombinedSnapshot schema = V6SchemaCombinedSnapshot.createV6(spec);
    final KvStoreAccessor db =
        MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables());
    return new CombinedKvStoreDao<>(
        db, schema, new V4FinalizedStateSnapshotStorageLogic<>(1), new StubMetricsSystem());
  }

  /**
   * Builds the checkpoint states of epochs {@code 0..count-1} by processing slots from a genesis
   * state. A random state cannot be used: its random slashings overflow epoch processing.
   */
  private void buildChain(final int count) {
    BeaconState state =
        ChainBuilder.create(
                spec, new MockStartValidatorKeyPairFactory().generateKeyPairs(0, VALIDATOR_COUNT))
            .generateGenesis()
            .getState();
    for (int epoch = 0; epoch < count; epoch++) {
      if (epoch > 0) {
        state = processToEpoch(state, epoch);
      }
      states.add(state);
      roots.add(dataStructureUtil.randomBytes32());
    }
  }

  private BeaconState processToEpoch(final BeaconState state, final int epoch) {
    try {
      return spec.processSlots(state, spec.computeStartSlotAtEpoch(epoch(epoch)));
    } catch (final SlotProcessingException | EpochProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes the nodes of epochs {@code from..to}, one transaction per epoch. */
  private void writeEpochs(final int from, final int to) {
    for (int epoch = from; epoch <= to; epoch++) {
      final int e = epoch;
      update(
          tx ->
              tx.writeNode(
                  roots.get(e), states.get(e), epoch(e), epoch(e), ancestorsBefore(e), false));
    }
  }

  /** Checkpoint roots of the epochs before {@code epoch}, nearest first. */
  private List<Bytes32> ancestorsBefore(final int epoch) {
    final List<Bytes32> ancestors = new ArrayList<>();
    for (int i = epoch - 1; i >= 0; i--) {
      ancestors.add(roots.get(i));
    }
    return ancestors;
  }

  private static List<Bytes32> prepend(final Bytes32 root, final List<Bytes32> roots) {
    final List<Bytes32> result = new ArrayList<>();
    result.add(root);
    result.addAll(roots);
    return result;
  }

  private void retain(final Optional<Bytes32> previousAnchor, final int newAnchorEpoch) {
    retain(previousAnchor, newAnchorEpoch, FinalizedRetention.anchorChain());
  }

  /**
   * Moves the anchor to {@code newAnchorEpoch}, finalizing the roots after the previous anchor up
   * to and including the new one (from epoch 0 when there is no previous anchor).
   */
  private void retain(
      final Optional<Bytes32> previousAnchor,
      final int newAnchorEpoch,
      final FinalizedRetention retention) {
    final int firstNewlyFinalized = previousAnchor.map(root -> roots.indexOf(root) + 1).orElse(0);
    final List<Bytes32> newlyFinalizedRoots =
        List.copyOf(roots.subList(firstNewlyFinalized, newAnchorEpoch + 1));
    update(
        tx ->
            tx.retainFinalizedChain(
                previousAnchor, roots.get(newAnchorEpoch), newlyFinalizedRoots, retention));
    for (final Bytes32 root : dag.reconstructionChain(dao, roots.get(newAnchorEpoch))) {
      assertThat(dao.getStateDiffNode(root)).isPresent();
    }
    assertDagConsistent();
  }

  /**
   * Moves the retention window start from {@code previousStart} (the history from epoch 0 when
   * empty) to {@code newStart}, every epoch's checkpoint block being the block of its own node.
   */
  private void retainWindow(final Optional<Integer> previousStart, final int newStart) {
    final int from = previousStart.orElse(0);
    update(
        tx ->
            tx.retainFinalizedChain(
                Optional.empty(),
                roots.get(newStart),
                List.of(),
                FinalizedRetention.window(
                    epoch(newStart),
                    previousStart.map(roots::get),
                    roots.subList(from, newStart + 1))));
    assertDagConsistent();
  }

  private double windowStartGauge() {
    return metricsSystem
        .getLabelledGauge(TekuMetricCategory.STORAGE, "state_diff_window_start_epoch")
        .getValue()
        .orElseThrow();
  }

  private void assertStoredEpochs(final Set<Integer> expected) {
    final Set<Integer> stored = new HashSet<>();
    for (int epoch = 0; epoch < roots.size(); epoch++) {
      if (dao.getStateDiffNode(roots.get(epoch)).isPresent()) {
        stored.add(epoch);
      }
    }
    assertThat(stored).isEqualTo(expected);
  }

  private static Set<Integer> union(final Set<Integer> a, final Set<Integer> b) {
    final Set<Integer> result = new HashSet<>(a);
    result.addAll(b);
    return result;
  }

  private static Set<Integer> range(final int from, final int to) {
    return IntStream.rangeClosed(from, to).boxed().collect(Collectors.toCollection(HashSet::new));
  }

  private void update(final Consumer<StateDiffDagTransaction> action) {
    try (final HotUpdater updater = dao.hotUpdater()) {
      final StateDiffDagTransaction transaction = dag.begin(dao, updater);
      action.accept(transaction);
      transaction.commit();
      updater.commit();
    }
  }

  private void updateDao(final Consumer<HotUpdater> action) {
    try (final HotUpdater updater = dao.hotUpdater()) {
      action.accept(updater);
      updater.commit();
    }
  }

  private StateDiffNodeRecord record(final Bytes32 root) {
    return StateDiffNodeRecordCodec.decode(dao.getStateDiffNode(root).orElseThrow());
  }

  private Set<Bytes32> supernodeHashes(final Bytes32 root) {
    return TreeDiffCodec.deserialize(record(root).payload()).supernodes().stream()
        .map(SupernodeEntry::hash)
        .collect(Collectors.toSet());
  }

  private static BeaconState withChangedValidator(final BeaconState state) {
    return state.updated(
        mutable ->
            mutable
                .getValidators()
                .update(
                    0,
                    validator ->
                        validator.withEffectiveBalance(
                            validator.getEffectiveBalance().minusMinZero(1))));
  }

  private static Set<Bytes32> supernodeHashes(
      final StateDiffDagTransaction transaction, final Bytes32 root) {
    return TreeDiffCodec.deserialize(transaction.reader().getNode(root).orElseThrow().payload())
        .supernodes()
        .stream()
        .map(SupernodeEntry::hash)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private void assertReconstructs(final int epoch) {
    assertReconstructs(roots.get(epoch), states.get(epoch));
  }

  /**
   * Reconstructs {@code root} from its whole chain, as after a restart, and through the base cache
   * of {@link #dag}, from the nearest cached node of the chain.
   */
  private void assertReconstructs(final Bytes32 root, final BeaconState expected) {
    for (final StateDiffDag reader : List.of(uncachedDag(), dag)) {
      final BeaconState reconstructed = reader.reconstruct(dao, root).orElseThrow();
      assertThat(reconstructed.hashTreeRoot()).isEqualTo(expected.hashTreeRoot());
      assertThat(reconstructed).isEqualTo(expected);
    }
  }

  /** A DAG over the same database with nothing cached, as after a restart. */
  private StateDiffDag uncachedDag() {
    return new StateDiffDag(spec, dag.getConfig(), metricsSystem);
  }

  private static UInt64 epoch(final int epoch) {
    return UInt64.valueOf(epoch);
  }

  private static StubMetricsSystem recordingMetrics(final Map<String, List<Double>> histograms) {
    return recordingMetrics(new StubMetricsSystem(), histograms);
  }

  /** {@code metrics}' counters, with the observations of every histogram recorded by name. */
  private static StubMetricsSystem recordingMetrics(
      final StubMetricsSystem metrics, final Map<String, List<Double>> histograms) {
    return new StubMetricsSystem() {
      @Override
      public LabelledMetric<Counter> createLabelledCounter(
          final MetricCategory category,
          final String name,
          final String help,
          final String... labelNames) {
        return metrics.createLabelledCounter(category, name, help, labelNames);
      }

      @Override
      public Histogram createHistogram(
          final MetricCategory category,
          final String name,
          final String help,
          final double[] buckets) {
        final List<Double> observations = histograms.computeIfAbsent(name, __ -> new ArrayList<>());
        return observations::add;
      }
    };
  }

  private long baseCache(final StubMetricsSystem metrics, final String result) {
    return metrics.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_base_cache_total", result);
  }

  private long nodeCount(final String state) {
    return metricsSystem.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_nodes_total", state);
  }

  private long snapshots(final String reason) {
    return metricsSystem.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_snapshots_total", reason);
  }

  private long sectionEntries(final String section) {
    return metricsSystem.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_section_entries_total", section);
  }

  /** The section spans of a stored payload, which re-encodes to the same bytes. */
  private TreeDiffCodec.SectionBytes sectionBytes(final StateDiffNodeRecord record) {
    final TreeDiffCodec.Encoded encoded =
        TreeDiffCodec.encode(TreeDiffCodec.deserialize(record.payload()), 0);
    assertThat(encoded.payload()).isEqualTo(record.payload());
    return encoded.sectionBytes();
  }

  private long sectionBytes(final String section) {
    return metricsSystem.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_section_bytes_total", section);
  }

  private long listBuilds() {
    return metricsSystem.getCounterValue(
        TekuMetricCategory.STORAGE, "state_diff_reconstruct_list_builds_total");
  }

  private long supernodeOps(final String op) {
    return metricsSystem.getLabelledCounterValue(
        TekuMetricCategory.STORAGE, "state_supernodes_total", op);
  }
}
