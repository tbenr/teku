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

package tech.pegasys.teku.storage.server.kvstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.safeJoin;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ONE;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ZERO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.storage.api.HotState;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.api.StorageUpdate;
import tech.pegasys.teku.storage.server.Database;
import tech.pegasys.teku.storage.server.DatabaseContext;
import tech.pegasys.teku.storage.server.StateStorageMode;
import tech.pegasys.teku.storage.server.TestDatabaseContext;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffNodeRecord;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffNodeRecordCodec;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationReport;
import tech.pegasys.teku.storage.store.StoreConfig;
import tech.pegasys.teku.storage.store.UpdatableStore;

@TestDatabaseContext
@Execution(ExecutionMode.CONCURRENT)
public class StateDiffDatabaseTest extends AbstractDatabaseTest {

  @TestTemplate
  public void stateDiffs_activatesOnExistingDatabaseAndStopsWritingLatestFinalizedState(
      final DatabaseContext context) throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    // Build a legacy database first
    final DatabaseContext legacyContext =
        new DatabaseContext(context.getDatabaseVersion(), context.isInMemoryStorage(), false);
    initialize(legacyContext, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());
    justifyAndFinalizeEpoch(checkpoint1.getEpoch(), checkpoint1BlockAndState);
    assertThat(dao().getLatestFinalizedState()).contains(checkpoint1BlockAndState.getState());
    assertThat(dao().getStateDiffMode()).isEmpty();
    assertThat(database.isStateDiffStorageActive()).isFalse();

    setDefaultStorage(storageSystem.restartedWithStateDiffs(true));

    assertThat(database.isStateDiffStorageActive()).isTrue();
    assertThat(dao().getStateDiffMode()).isPresent();
    assertThat(dao().getLatestFinalizedState()).isEmpty();
    final StateDiffNodeRecord anchorNode = stateDiffNode(checkpoint1BlockAndState.getRoot());
    assertThat(anchorNode.snapshot()).isTrue();
    assertThat(anchorNode.firstEpoch()).isEqualTo(checkpoint1.getEpoch());
    assertThat(anchorNode.lastEpoch()).isEqualTo(checkpoint1.getEpoch());
    final UpdatableStore restartedStore = recentChainData.getStore();
    assertThat(restartedStore.getFinalizedCheckpoint()).isEqualTo(checkpoint1);
    assertThat(restartedStore.getLatestFinalized().getState())
        .isEqualTo(checkpoint1BlockAndState.getState());

    // Finalizing again keeps LATEST_FINALIZED_STATE absent and stores the anchor as a node
    justifyAndFinalizeEpoch(checkpoint2.getEpoch(), checkpoint2BlockAndState);
    assertThat(dao().getLatestFinalizedState()).isEmpty();
    assertThat(database.getHotState(checkpoint2BlockAndState.getRoot()))
        .contains(checkpoint2BlockAndState.getState());
    assertStateDiffsVerify();

    // From now on the marker, not the flag, selects the storage
    setDefaultStorage(storageSystem.restartedWithStateDiffs(false));
    assertThat(database.isStateDiffStorageActive()).isTrue();
    assertThat(recentChainData.getStore().getLatestFinalized().getState())
        .isEqualTo(checkpoint2BlockAndState.getState());
  }

  @TestTemplate
  public void stateDiffs_freshDatabaseWritesAnchorSnapshot(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);

    assertThat(database.isStateDiffStorageActive()).isTrue();
    assertThat(dao().getStateDiffMode()).isPresent();
    assertThat(dao().getLatestFinalizedState()).isEmpty();
    final StateDiffNodeRecord anchorNode = stateDiffNode(genesisBlockAndState.getRoot());
    assertThat(anchorNode.snapshot()).isTrue();
    assertThat(anchorNode.stateRoot()).isEqualTo(genesisBlockAndState.getState().hashTreeRoot());
    assertThat(database.createMemoryStore().orElseThrow().stateDiffStorageActive()).isTrue();
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_hotStateIsReconstructedForCheckpointBlock(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());

    final List<SignedBlockAndState> checkpointBlocks =
        List.of(checkpoint1BlockAndState, checkpoint2BlockAndState, checkpoint3BlockAndState);
    storeStateDiffNodesOnChain(checkpointBlocks);

    for (final SignedBlockAndState checkpointBlock : checkpointBlocks) {
      assertThat(database.getHotState(checkpointBlock.getRoot()))
          .contains(checkpointBlock.getState());
    }
    // Nodes after the anchor snapshot are diffs
    assertThat(stateDiffNode(checkpoint3BlockAndState.getRoot()).snapshot()).isFalse();
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_finalizationKeepsAnchorChainAndDropsOrphans(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    final ChainBuilder fork = chainBuilder.fork();
    generateCheckpoints();
    // A fork built on genesis crossing into epoch 1: the store writes a node for its block 6
    final SignedBlockAndState forkCheckpointBlock = fork.generateBlockAtSlot(6);
    final SignedBlockAndState forkBlock = fork.generateBlockAtSlot(9);
    assertThat(forkCheckpointBlock.getRoot())
        .isNotEqualTo(chainBuilder.getBlockAndStateAtSlot(6).getRoot());
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());
    addBlocks(forkCheckpointBlock, forkBlock);
    assertThat(dao().getStateDiffNode(forkCheckpointBlock.getRoot())).isPresent();

    justifyAndFinalizeEpoch(checkpoint2.getEpoch(), checkpoint2BlockAndState);

    assertThat(dao().getStateDiffNode(forkCheckpointBlock.getRoot())).isEmpty();
    // The new anchor's reconstruction chain is kept: the anchor, on the start slot of epoch 2, got
    // its node from the store as a diff against epoch 1's, down to genesis
    assertThat(stateDiffNodeCount("anchor_created_late")).isZero();
    assertThat(stateDiffNode(checkpoint2BlockAndState.getRoot()).parentNodeRoot())
        .isEqualTo(checkpoint1BlockAndState.getRoot());
    assertThat(stateDiffReconstructionChain(checkpoint2BlockAndState.getRoot()))
        .contains(checkpoint1BlockAndState.getRoot(), genesisBlockAndState.getRoot());
    assertThat(database.getHotState(checkpoint2BlockAndState.getRoot()))
        .contains(checkpoint2BlockAndState.getState());
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_finalizingTwiceKeepsOnlyAnchorChainAndHotNodes(
      final DatabaseContext context) throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    // No state cache: a checkpoint block state is available only within its own transaction
    createStorageSystem(
        context,
        StateStorageMode.PRUNE,
        StoreConfig.builder().stateCacheSize(0).epochStateCacheSize(0).build(),
        false);
    initGenesis();
    // Every slot has a block except the first slot of epoch 5: the checkpoint block of an epoch is
    // the block on its start slot, its own node, except for epoch 5, whose checkpoint block is the
    // block at slot 39
    chainBuilder.generateBlocksUpToSlot(39);
    chainBuilder.generateBlockAtSlot(41);
    chainBuilder.generateBlocksUpToSlot(48);
    final List<SignedBlockAndState> checkpointBlocks =
        IntStream.rangeClosed(0, 6)
            .mapToObj(chainBuilder::getLatestBlockAndStateAtEpochBoundary)
            .toList();
    final List<Bytes32> checkpointRoots =
        checkpointBlocks.stream().map(SignedBlockAndState::getRoot).toList();
    final Bytes32 epoch4NodeRoot = checkpointRoots.get(4);
    final Bytes32 anchor5 = checkpointRoots.get(5);
    assertThat(checkpointBlocks.get(5).getSlot()).isEqualTo(UInt64.valueOf(39));
    final SignedBlockAndState epoch6NodeBlock = checkpointBlocks.get(6);

    // Nodes for epochs 1 to 4
    addBlocks(chainBuilder.streamBlocksAndStates(1, 39).toList());
    justifyAndFinalizeEpoch(UInt64.valueOf(2), checkpointBlocks.get(2));
    // The node for epoch 5 is skipped: its checkpoint block's state is gone when the first block of
    // the epoch arrives. So the node for epoch 6 diffs against epoch 4 and finalizing epoch 5
    // creates its node late
    addBlocks(chainBuilder.streamBlocksAndStates(40, 48).toList());
    assertThat(dao().getStateDiffNode(anchor5)).isEmpty();
    assertThat(stateDiffNode(epoch6NodeBlock.getRoot()).parentNodeRoot()).isEqualTo(epoch4NodeRoot);
    assertThat(stateDiffNodeCount("anchor_created_late")).isZero();

    justifyAndFinalizeEpoch(UInt64.valueOf(5), checkpointBlocks.get(5));

    // The late anchor node chains through its finalized ancestors, keeping node 6's parent
    assertThat(stateDiffNodeCount("anchor_created_late")).isEqualTo(1);
    assertThat(stateDiffNode(anchor5).prevNodeRoot()).isEqualTo(epoch4NodeRoot);
    final Set<Bytes32> expected = new HashSet<>(stateDiffReconstructionChain(anchor5));
    expected.add(epoch6NodeBlock.getRoot());
    assertThat(storedStateDiffNodeRoots()).isEqualTo(expected);
    assertThat(database.getHotState(anchor5)).contains(checkpointBlocks.get(5).getState());
    assertThat(database.getHotState(epoch6NodeBlock.getRoot()))
        .contains(epoch6NodeBlock.getState());
    assertStateDiffsVerify();

    // Finalizing again drops the node of epoch 5's anchor, which no retained node needs
    justifyAndFinalizeEpoch(UInt64.valueOf(6), checkpointBlocks.get(6));
    assertThat(storedStateDiffNodeRoots())
        .isEqualTo(stateDiffReconstructionChain(checkpointRoots.get(6)))
        .contains(epoch6NodeBlock.getRoot())
        .doesNotContain(anchor5);
    assertThat(database.getHotState(checkpointRoots.get(6)))
        .contains(checkpointBlocks.get(6).getState());
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_lateCoarseAnchorKeepsParentsOfLaterNodes(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    // No state cache: a checkpoint block state is available only within its own transaction
    createStorageSystem(
        context,
        StateStorageMode.PRUNE,
        StoreConfig.builder().stateCacheSize(0).epochStateCacheSize(0).build(),
        false);
    initGenesis();
    // Every slot has a block except the first slot of epoch 16, a level 16 epoch, whose checkpoint
    // block is the block at slot 127
    chainBuilder.generateBlocksUpToSlot(127);
    chainBuilder.generateBlockAtSlot(129);
    chainBuilder.generateBlocksUpToSlot(152);
    final List<SignedBlockAndState> checkpointBlocks =
        IntStream.rangeClosed(0, 19)
            .mapToObj(chainBuilder::getLatestBlockAndStateAtEpochBoundary)
            .toList();
    final List<Bytes32> checkpointRoots =
        checkpointBlocks.stream().map(SignedBlockAndState::getRoot).toList();
    final Bytes32 anchor16 = checkpointRoots.get(16);
    assertThat(checkpointBlocks.get(16).getSlot()).isEqualTo(UInt64.valueOf(127));

    // Nodes for epochs 1 to 15
    addBlocks(chainBuilder.streamBlocksAndStates(1, 127).toList());
    justifyAndFinalizeEpoch(UInt64.valueOf(2), checkpointBlocks.get(2));
    // The node for epoch 16 is skipped, so the node for epoch 17 diffs against epoch 15
    addBlocks(chainBuilder.streamBlocksAndStates(129, 152).toList());
    assertThat(dao().getStateDiffNode(anchor16)).isEmpty();
    assertThat(stateDiffNode(checkpointRoots.get(17)).parentNodeRoot())
        .isEqualTo(checkpointRoots.get(15));

    justifyAndFinalizeEpoch(UInt64.valueOf(16), checkpointBlocks.get(16));

    // The late anchor node is at the finest level whatever its epoch, so it keeps node 15
    assertThat(stateDiffNodeCount("anchor_created_late")).isEqualTo(1);
    final StateDiffNodeRecord lateNode = stateDiffNode(anchor16);
    assertThat(lateNode.snapshot()).isFalse();
    assertThat(lateNode.level()).isEqualTo(3);
    assertThat(lateNode.parentNodeRoot()).isEqualTo(checkpointRoots.get(15));
    assertThat(storedStateDiffNodeRoots()).contains(checkpointRoots.get(15));
    assertThat(database.getHotState(checkpointRoots.get(17)))
        .contains(checkpointBlocks.get(17).getState());
    assertStateDiffsVerify();

    // The next finalizations load every chain they need
    justifyAndFinalizeEpoch(UInt64.valueOf(17), checkpointBlocks.get(17));
    justifyAndFinalizeEpoch(UInt64.valueOf(18), checkpointBlocks.get(18));

    assertThat(stateDiffNodeCount("anchor_repaired")).isZero();
    final Set<Bytes32> expected =
        new HashSet<>(stateDiffReconstructionChain(checkpointRoots.get(18)));
    expected.add(checkpointRoots.get(19));
    assertThat(storedStateDiffNodeRoots()).isEqualTo(expected).doesNotContain(anchor16);
    for (int epoch = 17; epoch <= 19; epoch++) {
      assertThat(database.getHotState(checkpointRoots.get(epoch)))
          .contains(checkpointBlocks.get(epoch).getState());
    }
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_brokenAnchorChainIsRepairedOnFinalization(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());
    justifyAndFinalizeEpoch(checkpoint2.getEpoch(), checkpoint2BlockAndState);
    final Bytes32 anchor2 = checkpoint2BlockAndState.getRoot();
    final Bytes32 anchor3 = checkpoint3BlockAndState.getRoot();
    assertThat(stateDiffReconstructionChain(anchor2))
        .contains(checkpoint1BlockAndState.getRoot(), genesisBlockAndState.getRoot());
    assertThat(stateDiffNode(anchor3).parentNodeRoot()).isEqualTo(anchor2);

    // A node of the anchor's chain is lost: neither the anchor's chain nor the next one loads
    try (final KvStoreCombinedDao.HotUpdater updater = dao().hotUpdater()) {
      updater.deleteStateDiffNode(checkpoint1BlockAndState.getRoot());
      updater.commit();
    }

    justifyAndFinalizeEpoch(checkpoint3.getEpoch(), checkpoint3BlockAndState);

    assertThat(stateDiffNodeCount("anchor_repaired")).isEqualTo(1);
    assertThat(stateDiffNode(anchor3).snapshot()).isTrue();
    assertThat(dao().getStateDiffNode(anchor2)).isEmpty();
    assertThat(database.getHotState(anchor3)).contains(checkpoint3BlockAndState.getState());
    final UpdatableStore restartedStore = recreateStore();
    assertThat(restartedStore.getLatestFinalized().getState())
        .isEqualTo(checkpoint3BlockAndState.getState());
    // Finalizing again works without a repair
    chainBuilder.generateBlocksUpToSlot(33);
    final SignedBlockAndState checkpoint4BlockAndState = chainBuilder.getBlockAndStateAtSlot(32);
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(checkpoint3BlockAndState.getSlot().longValue() + 1, 33)
            .toList());
    justifyAndFinalizeEpoch(UInt64.valueOf(4), checkpoint4BlockAndState);
    assertThat(stateDiffNodeCount("anchor_repaired")).isZero();
    assertThat(stateDiffNode(checkpoint4BlockAndState.getRoot()).parentNodeRoot())
        .isEqualTo(anchor3);
    assertThat(database.getHotState(checkpoint4BlockAndState.getRoot()))
        .contains(checkpoint4BlockAndState.getState());
  }

  @TestTemplate
  public void stateDiffs_corruptHotNodeFallsBackToReplay(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    chainBuilder.generateBlocksUpToSlot(33);
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());
    final Bytes32 node2 = checkpoint2BlockAndState.getRoot();
    final Bytes32 node3 = checkpoint3BlockAndState.getRoot();
    assertThat(stateDiffNode(node3).parentNodeRoot()).isEqualTo(node2);
    // The node of checkpoint 2, above the genesis anchor, gets corrupted
    try (final KvStoreCombinedDao.HotUpdater updater = dao().hotUpdater()) {
      updater.putStateDiffNode(node2, Bytes.of(1, 2, 3));
      updater.commit();
    }

    final UpdatableStore restartedStore = recreateStore();

    assertThat(database.getHotState(node2)).isEmpty();
    assertThat(database.getHotState(node3)).isEmpty();
    // States above it are regenerated by replaying blocks from the anchor
    final SignedBlockAndState block = chainBuilder.getBlockAndStateAtSlot(20);
    assertThat(restartedStore.retrieveBlockState(block.getRoot()))
        .isCompletedWithValue(Optional.of(block.getState()));
    assertThat(restartedStore.retrieveBlockState(node3))
        .isCompletedWithValue(Optional.of(checkpoint3BlockAndState.getState()));

    // A later node whose diff base cannot be reconstructed is written as a snapshot instead
    addBlocks(chainBuilder.streamBlocksAndStates(25, 33).toList());
    final SignedBlockAndState checkpoint4BlockAndState = chainBuilder.getBlockAndStateAtSlot(32);
    final StateDiffNodeRecord node4 = stateDiffNode(checkpoint4BlockAndState.getRoot());
    assertThat(node4.snapshot()).isTrue();
    assertThat(node4.prevNodeRoot()).isEqualTo(node3);
    assertThat(database.getHotState(checkpoint4BlockAndState.getRoot()))
        .contains(checkpoint4BlockAndState.getState());
  }

  @TestTemplate
  public void stateDiffs_restartRecreatesStoreFromDag(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint2BlockAndState.getSlot().longValue())
            .toList());
    // The checkpoint block sits on the epoch's start slot: the store wrote its node on import, as a
    // diff against the genesis anchor, so finalization does not have to create it
    assertThat(stateDiffNode(checkpoint1BlockAndState.getRoot()).parentNodeRoot())
        .isEqualTo(genesisBlockAndState.getRoot());
    justifyAndFinalizeEpoch(checkpoint1.getEpoch(), checkpoint1BlockAndState);
    assertThat(stateDiffNodeCount("anchor_created_late")).isZero();

    final UpdatableStore result = recreateStore();

    assertThat(result.getFinalizedCheckpoint()).isEqualTo(checkpoint1);
    assertThat(result.getLatestFinalized().getState().hashTreeRoot())
        .isEqualTo(checkpoint1BlockAndState.getState().hashTreeRoot());
    assertThat(result.retrieveBlockState(checkpoint2BlockAndState.getRoot()))
        .isCompletedWithValue(Optional.of(checkpoint2BlockAndState.getState()));
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_doesNotWriteHotStatesColumn(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    final List<SignedBlockAndState> blocks =
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList();
    addBlocks(blocks);
    storeStateDiffNodesOnChain(List.of(checkpoint1BlockAndState));

    for (final SignedBlockAndState block : blocks) {
      assertThat(dao().getHotState(block.getRoot())).isEmpty();
    }
    assertThat(database.getHotState(checkpoint1BlockAndState.getRoot()))
        .contains(checkpoint1BlockAndState.getState());
  }

  @TestTemplate
  public void stateDiffs_everyBlockStateRegeneratesAfterRestart(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    // The canonical chain fills every slot through epoch 4; a fork leaves it after slot 12 and
    // skips slots, so its epoch crossings happen on different blocks
    chainBuilder.generateBlocksUpToSlot(12);
    final ChainBuilder fork = chainBuilder.fork();
    chainBuilder.generateBlocksUpToSlot(34);
    final List<SignedBlockAndState> forkBlocks =
        List.of(
            fork.generateBlockAtSlot(14),
            fork.generateBlockAtSlot(17),
            fork.generateBlockAtSlot(26),
            fork.generateBlockAtSlot(33));
    final List<SignedBlockAndState> blocks =
        new ArrayList<>(chainBuilder.streamBlocksAndStates(1, 34).toList());
    blocks.addAll(forkBlocks);
    blocks.sort(Comparator.comparing(SignedBlockAndState::getSlot));

    // Imported one block per transaction, as the node does, so every node goes through the store
    blocks.forEach(block -> addBlocks(block));
    // Finalizing moves the anchor, so later ancestor lists end at the new anchor. The anchor, on
    // the start slot of epoch 1, already has its node: finalization does not create it late
    final SignedBlockAndState anchor = chainBuilder.getLatestBlockAndStateAtEpochBoundary(1);
    assertThat(dao().getStateDiffNode(anchor.getRoot())).isPresent();
    justifyAndFinalizeEpoch(ONE, anchor);
    assertThat(stateDiffNodeCount("anchor_created_late")).isZero();
    final List<SignedBlockAndState> blocksAfterFinalization =
        chainBuilder.generateBlocksUpToSlot(42);
    blocksAfterFinalization.forEach(block -> addBlocks(block));

    // A node for the checkpoint block of every epoch on both branches, none in the legacy column:
    // on the canonical chain the blocks on epoch start slots, on the fork, which skips those
    // slots, the parents of the first blocks of its epochs
    final List<SignedBlockAndState> checkpointBlocks =
        List.of(
            chainBuilder.getBlockAndStateAtSlot(16),
            chainBuilder.getBlockAndStateAtSlot(24),
            chainBuilder.getBlockAndStateAtSlot(32),
            chainBuilder.getBlockAndStateAtSlot(40),
            forkBlocks.get(0),
            forkBlocks.get(1),
            forkBlocks.get(2));
    for (final SignedBlockAndState checkpointBlock : checkpointBlocks) {
      assertThat(dao().getStateDiffNode(checkpointBlock.getRoot()))
          .describedAs("node of checkpoint block at slot %s", checkpointBlock.getSlot())
          .isPresent();
      assertThat(database.getHotState(checkpointBlock.getRoot()))
          .contains(checkpointBlock.getState());
    }
    final List<SignedBlockAndState> hotBlocks =
        Stream.concat(
                Stream.concat(blocks.stream(), blocksAfterFinalization.stream())
                    .filter(block -> block.getSlot().isGreaterThan(anchor.getSlot())),
                Stream.of(anchor))
            .toList();
    for (final SignedBlockAndState block : hotBlocks) {
      assertThat(dao().getHotState(block.getRoot())).isEmpty();
    }
    final StateDiffVerificationReport report = assertStateDiffsVerify();
    assertThat(report.anchorRoot()).contains(anchor.getRoot());

    final UpdatableStore restartedStore = recreateStore();

    assertThat(restartedStore.getLatestFinalized().getRoot()).isEqualTo(anchor.getRoot());
    for (final SignedBlockAndState block : hotBlocks) {
      assertThat(restartedStore.retrieveBlockState(block.getRoot()))
          .describedAs("state of block at slot %s", block.getSlot())
          .isCompletedWithValue(Optional.of(block.getState()));
    }
  }

  @TestTemplate
  public void stateDiffs_anchorAfterEmptyFirstSlotReconstructs(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    // The first slot of epoch 1 is empty: its checkpoint block is the block at slot 7
    chainBuilder.generateBlocksUpToSlot(7);
    chainBuilder.generateBlockAtSlot(9);
    chainBuilder.generateBlocksUpToSlot(20);
    chainBuilder.streamBlocksAndStates(1, 20).forEach(block -> addBlocks(block));
    final SignedBlockAndState anchor = chainBuilder.getLatestBlockAndStateAtEpochBoundary(1);
    assertThat(anchor.getSlot()).isEqualTo(UInt64.valueOf(7));

    justifyAndFinalizeEpoch(ONE, anchor);

    // The store wrote the anchor's node on import, so finalization did not have to create it
    assertThat(stateDiffNodeCount("anchor_created_late")).isZero();
    assertThat(database.getHotState(anchor.getRoot())).contains(anchor.getState());
    assertStateDiffsVerify();
    final UpdatableStore restartedStore = recreateStore();
    assertThat(restartedStore.getLatestFinalized().getState()).isEqualTo(anchor.getState());
    final SignedBlockAndState head = chainBuilder.getBlockAndStateAtSlot(20);
    assertThat(restartedStore.retrieveBlockState(head.getRoot()))
        .isCompletedWithValue(Optional.of(head.getState()));
  }

  @TestTemplate
  public void stateDiffs_supernodeRefCountsBatchReadMatchesSingleReads(
      final DatabaseContext context) throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint3BlockAndState.getSlot().longValue())
            .toList());
    storeStateDiffNodesOnChain(
        List.of(checkpoint1BlockAndState, checkpoint2BlockAndState, checkpoint3BlockAndState));
    final List<Bytes32> hashes = new ArrayList<>();
    try (final Stream<ColumnEntry<Bytes32, UInt64>> counts =
        dao().streamStateSupernodeRefCounts()) {
      counts.forEach(entry -> hashes.add(entry.getKey()));
    }
    assertThat(hashes).isNotEmpty();
    hashes.add(dataStructureUtil.randomBytes32());

    final Map<Bytes32, UInt64> singleReads = new HashMap<>();
    hashes.forEach(
        hash -> dao().getStateSupernodeRefCount(hash).ifPresent(c -> singleReads.put(hash, c)));
    assertThat(singleReads).hasSize(hashes.size() - 1);
    assertThat(dao().getStateSupernodeRefCounts(hashes)).isEqualTo(singleReads);
  }

  @TestTemplate
  public void stateDiffs_archiveServesFinalizedStatesFromDagWithoutLegacyStates(
      final DatabaseContext context) throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.ARCHIVE);
    // The first slot of epoch 2 is empty: its checkpoint block is the block at slot 15
    chainBuilder.generateBlocksUpToSlot(15);
    chainBuilder.generateBlockAtSlot(17);
    chainBuilder.generateBlocksUpToSlot(40);
    chainBuilder.streamBlocksAndStates(1, 40).forEach(block -> addBlocks(block));
    final SignedBlockAndState anchor = chainBuilder.getLatestBlockAndStateAtEpochBoundary(4);

    justifyAndFinalizeEpoch(UInt64.valueOf(4), anchor);

    // The DAG holds the finalized states: none is written to the legacy finalized state storage
    try (final Stream<UInt64> slots = database.streamFinalizedStateSlots(ZERO, anchor.getSlot())) {
      assertThat(slots).isEmpty();
    }
    // History starts at the genesis anchor
    assertThat(database.getEarliestAvailableFinalizedStateSlot()).contains(ZERO);
    assertFinalizedStatesServedFromDag(ZERO, anchor.getSlot());
    // Without any state in memory
    restartStorage();
    assertFinalizedStatesServedFromDag(ZERO, anchor.getSlot());
  }

  @TestTemplate
  public void stateDiffs_archiveWindowKeepsWindowNodesAndOldestChain(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    final int windowEpochs = 3;
    createStorageSystem(
        context,
        StateStorageMode.ARCHIVE,
        StoreConfig.builder().stateCacheSize(32).build(),
        false,
        (long) windowEpochs * spec.getSlotsPerEpoch(ZERO));
    initGenesis();
    // Nodes below epoch 16 are all at the finest level, so no node leaves the window before the
    // oldest node's chain passes through the epoch 16 node instead of its predecessors
    final int headEpoch = 22;
    chainBuilder.generateBlocksUpToSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(headEpoch)));
    chainBuilder.streamBlocksAndStates(1).forEach(block -> addBlocks(block));

    for (final int finalizedEpoch : List.of(4, 9, 18, 21)) {
      final SignedBlockAndState anchor =
          chainBuilder.getLatestBlockAndStateAtEpochBoundary(finalizedEpoch);
      justifyAndFinalizeEpoch(UInt64.valueOf(finalizedEpoch), anchor);

      // Every node of the window and the chain of its oldest node, which is all the window needs
      final int windowStart = finalizedEpoch - windowEpochs;
      final Set<Bytes32> expected =
          new HashSet<>(
              stateDiffReconstructionChain(
                  chainBuilder.getLatestBlockAndStateAtEpochBoundary(windowStart).getRoot()));
      IntStream.rangeClosed(windowStart, headEpoch)
          .mapToObj(epoch -> chainBuilder.getLatestBlockAndStateAtEpochBoundary(epoch).getRoot())
          .forEach(expected::add);
      assertThat(storedStateDiffNodeRoots())
          .describedAs("nodes after finalizing epoch %s", finalizedEpoch)
          .isEqualTo(expected);
      final UInt64 windowStartSlot = spec.computeStartSlotAtEpoch(UInt64.valueOf(windowStart));
      assertThat(database.getEarliestAvailableFinalizedStateSlot()).contains(windowStartSlot);
      assertThat(database.getLatestAvailableFinalizedState(windowStartSlot.minus(ONE))).isEmpty();
      assertFinalizedStatesServedFromDag(windowStartSlot, anchor.getSlot());
      assertStateDiffsVerify();
    }
    assertThat(stateDiffNodeCount("deleted")).isPositive();
    assertThat(
            storageSystem
                .getDatabaseMetricsSystem()
                .getLabelledGauge(TekuMetricCategory.STORAGE, "state_diff_window_start_epoch")
                .getValue())
        .hasValue(18);

    // The window survives a restart
    restartStorage();
    assertThat(database.getEarliestAvailableFinalizedStateSlot())
        .contains(spec.computeStartSlotAtEpoch(UInt64.valueOf(18)));
    assertFinalizedStatesServedFromDag(
        spec.computeStartSlotAtEpoch(UInt64.valueOf(18)),
        chainBuilder.getLatestBlockAndStateAtEpochBoundary(21).getSlot());
  }

  @TestTemplate
  public void stateDiffs_archiveWindowDrainsBacklogInBoundedSteps(final DatabaseContext context)
      throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    // A window of one epoch
    createStorageSystem(
        context,
        StateStorageMode.ARCHIVE,
        StoreConfig.builder().stateCacheSize(32).build(),
        false,
        spec.getSlotsPerEpoch(ZERO));
    initGenesis();
    // Epochs 2 to 299 are empty: the block at slot 9 is their checkpoint block
    chainBuilder.generateBlocksUpToSlot(9);
    final UInt64 epoch300StartSlot = spec.computeStartSlotAtEpoch(UInt64.valueOf(300));
    chainBuilder.generateBlockAtSlot(epoch300StartSlot);
    chainBuilder.generateBlocksUpToSlot(epoch300StartSlot.plus(17));
    chainBuilder.streamBlocksAndStates(1).forEach(block -> addBlocks(block));
    final SignedBlockAndState gapCheckpointBlock = chainBuilder.getBlockAndStateAtSlot(9);
    final SignedBlockAndState epoch1CheckpointBlock = chainBuilder.getBlockAndStateAtSlot(8);
    assertThat(stateDiffNode(gapCheckpointBlock.getRoot()).lastEpoch())
        .isEqualTo(UInt64.valueOf(299));

    justifyAndFinalizeEpoch(
        UInt64.valueOf(300), chainBuilder.getLatestBlockAndStateAtEpochBoundary(300));

    // The window should start at epoch 299, but at most 256 epochs leave per finalization
    assertThat(dao().getStateDiffWindowStart()).contains(UInt64.valueOf(256));
    assertThat(storedStateDiffNodeRoots())
        .contains(gapCheckpointBlock.getRoot())
        .doesNotContain(genesisBlockAndState.getRoot(), epoch1CheckpointBlock.getRoot());
    final UInt64 epoch256StartSlot = spec.computeStartSlotAtEpoch(UInt64.valueOf(256));
    assertThat(database.getLatestAvailableFinalizedState(epoch256StartSlot))
        .map(BeaconState::hashTreeRoot)
        .contains(gapCheckpointBlock.getState().hashTreeRoot());
    assertThat(database.getLatestAvailableFinalizedState(epoch256StartSlot.minus(ONE))).isEmpty();

    // The next finalization drains the rest
    justifyAndFinalizeEpoch(
        UInt64.valueOf(302), chainBuilder.getLatestBlockAndStateAtEpochBoundary(302));

    assertThat(dao().getStateDiffWindowStart()).contains(UInt64.valueOf(301));
    assertThat(database.getEarliestAvailableFinalizedStateSlot())
        .contains(spec.computeStartSlotAtEpoch(UInt64.valueOf(301)));
    assertThat(database.getLatestAvailableFinalizedState(epoch300StartSlot)).isEmpty();
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void stateDiffs_archiveWindowStartWithSkippedNodeReadsOldestRetainedNode(
      final DatabaseContext context) throws IOException {
    assumeTrue(context.isStateDiffsEnabled());
    // A window of three epochs, no state cache: a checkpoint block state is available only within
    // its own transaction
    createStorageSystem(
        context,
        StateStorageMode.ARCHIVE,
        StoreConfig.builder().stateCacheSize(0).epochStateCacheSize(0).build(),
        false,
        3L * spec.getSlotsPerEpoch(ZERO));
    initGenesis();
    // Every slot has a block except the first slot of epoch 5, whose checkpoint block is the block
    // at slot 39
    chainBuilder.generateBlocksUpToSlot(39);
    chainBuilder.generateBlockAtSlot(41);
    chainBuilder.generateBlocksUpToSlot(64);
    final SignedBlockAndState epoch5CheckpointBlock =
        chainBuilder.getLatestBlockAndStateAtEpochBoundary(5);
    assertThat(epoch5CheckpointBlock.getSlot()).isEqualTo(UInt64.valueOf(39));
    final SignedBlockAndState epoch4CheckpointBlock =
        chainBuilder.getLatestBlockAndStateAtEpochBoundary(4);
    final SignedBlockAndState anchor = chainBuilder.getLatestBlockAndStateAtEpochBoundary(8);
    addBlocks(chainBuilder.streamBlocksAndStates(1, 39).toList());
    // The node for epoch 5 is skipped: its checkpoint block's state is gone when the first block of
    // the epoch arrives
    addBlocks(chainBuilder.streamBlocksAndStates(41, 64).toList());
    assertThat(dao().getStateDiffNode(epoch5CheckpointBlock.getRoot())).isEmpty();

    justifyAndFinalizeEpoch(UInt64.valueOf(8), anchor);

    // The window starts at epoch 5, so its oldest retained node is epoch 4's, below the start
    assertThat(dao().getStateDiffWindowStart()).contains(UInt64.valueOf(5));
    final UInt64 windowStartSlot = spec.computeStartSlotAtEpoch(UInt64.valueOf(5));
    assertThat(database.getEarliestAvailableFinalizedStateSlot()).contains(windowStartSlot);
    assertThat(storedStateDiffNodeRoots()).contains(epoch4CheckpointBlock.getRoot());
    // Reads of the window start epoch fall back to that node, and its blocks are replayed on it
    for (UInt64 slot = windowStartSlot;
        slot.isLessThan(spec.computeStartSlotAtEpoch(UInt64.valueOf(6)));
        slot = slot.increment()) {
      assertThat(database.getLatestAvailableFinalizedState(slot))
          .describedAs("stored state for slot %s", slot)
          .map(BeaconState::hashTreeRoot)
          .contains(epoch4CheckpointBlock.getState().hashTreeRoot());
      final SignedBlockAndState block = chainBuilder.getBlockAndStateAtSlot(slot);
      if (block != null) {
        assertThat(safeJoin(storageSystem.chainStorage().getLatestFinalizedStateAtSlot(slot)))
            .describedAs("regenerated state for slot %s", slot)
            .map(BeaconState::hashTreeRoot)
            .contains(block.getState().hashTreeRoot());
      }
    }
    // Reads below the window start stay empty
    assertThat(database.getLatestAvailableFinalizedState(windowStartSlot.minus(ONE))).isEmpty();
    assertStateDiffsVerify();
  }

  @TestTemplate
  public void legacyBehaviourUnchangedWhenDisabled(final DatabaseContext context)
      throws IOException {
    assumeFalse(context.isStateDiffsEnabled());
    initialize(context, StateStorageMode.PRUNE);
    generateCheckpoints();
    addBlocks(
        chainBuilder
            .streamBlocksAndStates(1, checkpoint2BlockAndState.getSlot().longValue())
            .toList());
    justifyAndFinalizeEpoch(checkpoint1.getEpoch(), checkpoint1BlockAndState);

    assertThat(database.isStateDiffStorageActive()).isFalse();
    assertThat(dao().getStateDiffMode()).isEmpty();
    assertThat(dao().getLatestFinalizedState()).contains(checkpoint1BlockAndState.getState());
    try (final Stream<ColumnEntry<Bytes32, Bytes>> nodes = dao().streamStateDiffNodes()) {
      assertThat(nodes).isEmpty();
    }
    assertThat(database.createMemoryStore().orElseThrow().stateDiffStorageActive()).isFalse();
    assertThatThrownBy(database::verifyStateDiffs).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Checks that every slot from {@code fromSlot} to {@code toSlot} reads the state of its epoch's
   * checkpoint block from the database, that the state of every block in that range is regenerated
   * from it, and that the root of that state is indexed.
   */
  private void assertFinalizedStatesServedFromDag(final UInt64 fromSlot, final UInt64 toSlot) {
    for (UInt64 slot = fromSlot; slot.isLessThanOrEqualTo(toSlot); slot = slot.increment()) {
      final SignedBlockAndState checkpointBlock =
          chainBuilder.getLatestBlockAndStateAtEpochBoundary(spec.computeEpochAtSlot(slot));
      assertThat(database.getLatestAvailableFinalizedState(slot))
          .describedAs("stored state for slot %s", slot)
          .map(BeaconState::hashTreeRoot)
          .contains(checkpointBlock.getState().hashTreeRoot());
      final SignedBlockAndState block = chainBuilder.getBlockAndStateAtSlot(slot);
      if (block == null) {
        continue;
      }
      assertThat(safeJoin(storageSystem.chainStorage().getLatestFinalizedStateAtSlot(slot)))
          .describedAs("regenerated state for slot %s", slot)
          .map(BeaconState::hashTreeRoot)
          .contains(block.getState().hashTreeRoot());
      assertThat(database.getSlotForFinalizedStateRoot(block.getState().hashTreeRoot()))
          .contains(slot);
    }
  }

  /**
   * Checks the stored DAG with {@link Database#verifyStateDiffs()}: supernode reference counts,
   * supernode content, parent pointers and the reconstruction of the finalized anchor.
   */
  private StateDiffVerificationReport assertStateDiffsVerify() {
    final StateDiffVerificationReport report = database.verifyStateDiffs();
    assertThat(report.hasProblems()).describedAs("%s", report).isFalse();
    return report;
  }

  /**
   * {@code storage_state_diff_nodes_total{state}} of the current database, counted since it was
   * opened.
   */
  private long stateDiffNodeCount(final String state) {
    return storageSystem
        .getDatabaseMetricsSystem()
        .getLabelledCounterValue(TekuMetricCategory.STORAGE, "state_diff_nodes_total", state);
  }

  private StateDiffNodeRecord stateDiffNode(final Bytes32 blockRoot) {
    return StateDiffNodeRecordCodec.decode(dao().getStateDiffNode(blockRoot).orElseThrow());
  }

  /** The nodes needed to reconstruct {@code blockRoot}, following parent pointers to a snapshot. */
  private Set<Bytes32> stateDiffReconstructionChain(final Bytes32 blockRoot) {
    final Set<Bytes32> chain = new HashSet<>();
    Bytes32 current = blockRoot;
    while (true) {
      chain.add(current);
      final StateDiffNodeRecord record = stateDiffNode(current);
      if (record.snapshot()) {
        return chain;
      }
      current = record.parentNodeRoot();
    }
  }

  private Set<Bytes32> storedStateDiffNodeRoots() {
    try (final Stream<ColumnEntry<Bytes32, Bytes>> nodes = dao().streamStateDiffNodes()) {
      return nodes.map(ColumnEntry::getKey).collect(Collectors.toSet());
    }
  }

  /**
   * Stores a node for each given block, serving the epoch of its slot, chained from the genesis
   * anchor, through a storage update that bypasses the store. The store itself keys nodes by the
   * parent of the first block of each epoch, see {@code
   * stateDiffs_everyBlockStateRegeneratesAfterRestart}.
   */
  private void storeStateDiffNodesOnChain(final List<SignedBlockAndState> checkpointBlocks) {
    final List<Bytes32> ancestorCheckpointRoots = new ArrayList<>();
    ancestorCheckpointRoots.add(genesisBlockAndState.getRoot());
    for (final SignedBlockAndState checkpointBlock : checkpointBlocks) {
      storeStateDiffNode(
          checkpointBlock,
          spec.computeEpochAtSlot(checkpointBlock.getSlot()),
          List.copyOf(ancestorCheckpointRoots));
      ancestorCheckpointRoots.addFirst(checkpointBlock.getRoot());
    }
  }

  private void storeStateDiffNode(
      final SignedBlockAndState checkpointBlock,
      final UInt64 epoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    database.update(
        new StorageUpdate(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Map.of(
                checkpointBlock.getRoot(),
                HotState.withNodeInfo(
                    checkpointBlock.getState(),
                    new HotStateNodeInfo(epoch, epoch, ancestorCheckpointRoots))),
            Map.of(),
            Map.of(),
            Optional.empty(),
            Map.of(),
            Map.of(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false,
            false,
            false));
  }
}
