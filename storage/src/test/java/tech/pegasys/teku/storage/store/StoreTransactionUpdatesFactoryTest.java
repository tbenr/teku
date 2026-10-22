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

package tech.pegasys.teku.storage.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SyncAsyncRunner.SYNC_RUNNER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.pegasys.teku.dataproviders.lookup.EarliestBlobSidecarSlotProvider;
import tech.pegasys.teku.dataproviders.lookup.StateAndBlockSummaryProvider;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.api.StorageUpdate;
import tech.pegasys.teku.storage.api.StorageUpdateChannel;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;
import tech.pegasys.teku.storage.api.UpdateResult;

class StoreTransactionUpdatesFactoryTest {

  private final Spec spec = TestSpecFactory.createMinimalDeneb();
  private final ChainBuilder chainBuilder = ChainBuilder.create(spec);
  private final StubMetricsSystem metricsSystem = new StubMetricsSystem();

  @Test
  void hotBlocksShouldNotContainPrunedRoots() {
    final SignedBlockAndState genesis = chainBuilder.generateGenesis();
    final Checkpoint genesisCheckpoint = chainBuilder.getCurrentCheckpointForEpoch(0);

    final UpdatableStore store =
        StoreBuilder.create()
            .asyncRunner(SYNC_RUNNER)
            .metricsSystem(new StubMetricsSystem())
            .specProvider(spec)
            .blockProvider(roots -> SafeFuture.completedFuture(Collections.emptyMap()))
            .earliestBlobSidecarSlotProvider(EarliestBlobSidecarSlotProvider.NOOP)
            .stateProvider(StateAndBlockSummaryProvider.NOOP)
            .anchor(Optional.empty())
            .genesisTime(genesis.getState().getGenesisTime())
            .time(genesis.getState().getGenesisTime())
            .latestFinalized(AnchorPoint.create(spec, genesisCheckpoint, genesis))
            .justifiedCheckpoint(genesisCheckpoint)
            .bestJustifiedCheckpoint(genesisCheckpoint)
            .blockInformation(
                Map.of(
                    genesis.getRoot(),
                    new StoredBlockMetadata(
                        genesis.getSlot(),
                        genesis.getRoot(),
                        genesis.getParentRoot(),
                        genesis.getStateRoot(),
                        genesis.getExecutionBlockNumber(),
                        genesis.getExecutionBlockHash(),
                        Optional.of(spec.calculateBlockCheckpoints(genesis.getState())))))
            .storeConfig(StoreConfig.createDefault())
            .votes(Collections.emptyMap())
            .latestCanonicalBlockRoot(Optional.empty())
            .build();

    // Generate blocks for multiple epochs to trigger finalization
    final UInt64 epoch3Slot = spec.computeStartSlotAtEpoch(UInt64.valueOf(3));
    chainBuilder.generateBlocksUpToSlot(epoch3Slot);

    final StorageUpdateChannel channel = mock(StorageUpdateChannel.class);
    when(channel.onStorageUpdate(any())).thenReturn(SafeFuture.completedFuture(UpdateResult.EMPTY));

    // Start a transaction and add all blocks
    final UpdatableStore.StoreTransaction tx = store.startTransaction(channel);
    chainBuilder
        .streamBlocksAndStates(1, chainBuilder.getLatestSlot().longValue())
        .forEach(
            blockAndState ->
                tx.putBlockAndState(
                    blockAndState, spec.calculateBlockCheckpoints(blockAndState.getState())));

    // Set finalized checkpoint to epoch 2
    final Checkpoint finalizedCheckpoint =
        chainBuilder.getCurrentCheckpointForEpoch(UInt64.valueOf(2));
    tx.setFinalizedCheckpoint(finalizedCheckpoint, false);

    tx.commit().join();

    // Verify we captured a storage update
    final ArgumentCaptor<StorageUpdate> captor = ArgumentCaptor.forClass(StorageUpdate.class);
    verify(channel).onStorageUpdate(captor.capture());
    final StorageUpdate storageUpdate = captor.getValue();

    // Get the finalized block
    final SignedBlockAndState finalizedBlockAndState =
        chainBuilder.getBlockAndState(finalizedCheckpoint.getRoot()).orElseThrow();

    // Collect all block roots that should have been pruned (blocks at or before finalized slot,
    // except the finalized block itself)
    final UInt64 finalizedSlot = finalizedBlockAndState.getSlot();
    final List<Bytes32> rootsThatShouldBePruned =
        chainBuilder
            .streamBlocksAndStates(1, finalizedSlot.longValue())
            .map(SignedBlockAndState::getRoot)
            .filter(root -> !root.equals(finalizedBlockAndState.getRoot()))
            .toList();

    // Verify that the pruned roots are really pruned
    assertThat(rootsThatShouldBePruned).isNotEmpty();
    for (final Bytes32 prunedRoot : rootsThatShouldBePruned) {
      assertThat(storageUpdate.getHotBlocks())
          .describedAs("Hot blocks should not contain pruned root %s", prunedRoot)
          .doesNotContainKey(prunedRoot);
    }

    // Also verify the pruned roots are actually recorded in the deleted hot blocks
    assertThat(storageUpdate.getDeletedHotBlocks().keySet()).containsAll(rootsThatShouldBePruned);
  }

  @Test
  void diffMode_persistsCheckpointBlockStateWithAncestors() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    // Epochs 1 and 2 start with a block; the first slot of epoch 3 is empty
    chainBuilder.generateBlocksUpToSlot(23);
    chainBuilder.generateBlockAtSlot(25);
    final SignedBlockAndState checkpointBlock1 = chainBuilder.getBlockAndStateAtSlot(8);
    final SignedBlockAndState checkpointBlock2 = chainBuilder.getBlockAndStateAtSlot(16);
    final SignedBlockAndState checkpointBlock3 = chainBuilder.getBlockAndStateAtSlot(23);
    final Bytes32 block7Root = chainBuilder.getBlockAtSlot(7).getRoot();
    final Bytes32 block15Root = chainBuilder.getBlockAtSlot(15).getRoot();

    // One transaction per block, as on import: a parent state comes from the store's cache
    final List<StorageUpdate> updates = importOneBlockPerTransaction(store, 1, 25);

    final StorageUpdate epoch1Update = updates.get(7);
    assertThat(epoch1Update.getHotStates())
        .containsExactly(Map.entry(checkpointBlock1.getRoot(), checkpointBlock1.getState()));
    // Nearest first, ending with the finalized anchor root
    assertThat(epoch1Update.getHotStateNodeInfos())
        .containsExactly(
            Map.entry(
                checkpointBlock1.getRoot(),
                new HotStateNodeInfo(
                    UInt64.ONE, UInt64.ONE, List.of(block7Root, genesis.getRoot()))));

    final StorageUpdate epoch2Update = updates.get(15);
    assertThat(epoch2Update.getHotStates())
        .containsExactly(Map.entry(checkpointBlock2.getRoot(), checkpointBlock2.getState()));
    assertThat(epoch2Update.getHotStateNodeInfos())
        .containsExactly(
            Map.entry(
                checkpointBlock2.getRoot(),
                new HotStateNodeInfo(
                    UInt64.valueOf(2),
                    UInt64.valueOf(2),
                    List.of(
                        block15Root, checkpointBlock1.getRoot(), block7Root, genesis.getRoot()))));

    // The first slot of epoch 3 is empty: the parent of its first block is its checkpoint block
    final StorageUpdate epoch3Update = updates.getLast();
    assertThat(epoch3Update.getHotStates())
        .containsExactly(Map.entry(checkpointBlock3.getRoot(), checkpointBlock3.getState()));
    assertThat(epoch3Update.getHotStateNodeInfos().get(checkpointBlock3.getRoot()))
        .isEqualTo(
            new HotStateNodeInfo(
                UInt64.valueOf(3),
                UInt64.valueOf(3),
                List.of(
                    checkpointBlock2.getRoot(),
                    block15Root,
                    checkpointBlock1.getRoot(),
                    block7Root,
                    genesis.getRoot())));

    // No other block crosses an epoch boundary
    for (int i = 0; i < updates.size() - 1; i++) {
      if (i != 7 && i != 15) {
        assertThat(updates.get(i).getHotStates()).isEmpty();
        assertThat(updates.get(i).getHotStateNodeInfos()).isEmpty();
      }
    }
  }

  @Test
  void diffMode_blockAtEpochStartIsItsOwnNode() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    final SignedBlockAndState block3 = chainBuilder.generateBlockAtSlot(3);
    final SignedBlockAndState epochStartBlock = chainBuilder.generateBlockAtSlot(8);

    commitAndCapture(store, List.of(block3));
    final StorageUpdate update = commitAndCapture(store, List.of(epochStartBlock));

    // The parent serves no epoch: there is no empty epoch in between
    assertThat(update.getHotStates())
        .containsExactly(Map.entry(epochStartBlock.getRoot(), epochStartBlock.getState()));
    assertThat(update.getHotStateNodeInfos())
        .containsExactly(
            Map.entry(
                epochStartBlock.getRoot(),
                new HotStateNodeInfo(
                    UInt64.ONE, UInt64.ONE, List.of(block3.getRoot(), genesis.getRoot()))));
  }

  @Test
  void diffMode_emptyFirstSlotUsesParent() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    final SignedBlockAndState block5 = chainBuilder.generateBlockAtSlot(5);
    final SignedBlockAndState block10 = chainBuilder.generateBlockAtSlot(10);

    commitAndCapture(store, List.of(block5));
    final StorageUpdate update = commitAndCapture(store, List.of(block10));

    assertThat(update.getHotStates())
        .containsExactly(Map.entry(block5.getRoot(), block5.getState()));
    assertThat(update.getHotStateNodeInfos())
        .containsExactly(
            Map.entry(
                block5.getRoot(),
                new HotStateNodeInfo(UInt64.ONE, UInt64.ONE, List.of(genesis.getRoot()))));
  }

  @Test
  void diffMode_emptyEpochsServedByParentAndStartSlotBlock() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    // Epochs 1 and 2 are empty; the next block is on the start slot of epoch 3
    final SignedBlockAndState block5 = chainBuilder.generateBlockAtSlot(5);
    final SignedBlockAndState block24 = chainBuilder.generateBlockAtSlot(24);

    commitAndCapture(store, List.of(block5));
    final StorageUpdate update = commitAndCapture(store, List.of(block24));

    assertThat(update.getHotStates())
        .containsOnly(
            Map.entry(block5.getRoot(), block5.getState()),
            Map.entry(block24.getRoot(), block24.getState()));
    assertThat(update.getHotStateNodeInfos())
        .containsOnly(
            Map.entry(
                block5.getRoot(),
                new HotStateNodeInfo(UInt64.ONE, UInt64.valueOf(2), List.of(genesis.getRoot()))),
            Map.entry(
                block24.getRoot(),
                new HotStateNodeInfo(
                    UInt64.valueOf(3),
                    UInt64.valueOf(3),
                    List.of(block5.getRoot(), genesis.getRoot()))));
  }

  @Test
  void diffMode_servedEpochsSpanEmptyEpochs() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    // A block exactly on the start slot of epoch 1, then epochs 2 and 3 are empty until slot 25
    final SignedBlockAndState block3 = chainBuilder.generateBlockAtSlot(3);
    final SignedBlockAndState epochStartBlock = chainBuilder.generateBlockAtSlot(8);
    final SignedBlockAndState block25 = chainBuilder.generateBlockAtSlot(25);

    // All in one transaction: the checkpoint block states come from the transaction and the
    // ancestors are walked through the transaction's blocks
    final StorageUpdate update = commitAndCapture(store, List.of(block3, epochStartBlock, block25));

    // Block 3 serves no epoch: epoch 1 starts with a block
    assertThat(update.getHotStates())
        .containsOnly(Map.entry(epochStartBlock.getRoot(), epochStartBlock.getState()));
    assertThat(update.getHotStateNodeInfos())
        .containsOnly(
            // On an epoch start slot: serves its own epoch and the empty ones up to its child's
            Map.entry(
                epochStartBlock.getRoot(),
                new HotStateNodeInfo(
                    UInt64.ONE, UInt64.valueOf(3), List.of(block3.getRoot(), genesis.getRoot()))));
  }

  @Test
  void diffMode_lastEpochIsMaximumOverChildrenOnForks() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    final SignedBlockAndState genesis = chainBuilder.getGenesis();
    final SignedBlockAndState checkpointBlock = chainBuilder.generateBlockAtSlot(6);
    final ChainBuilder fork = chainBuilder.fork();
    final SignedBlockAndState childInEpoch1 = chainBuilder.generateBlockAtSlot(9);
    final SignedBlockAndState childInEpoch2 = fork.generateBlockAtSlot(17);

    final StorageUpdate update =
        commitAndCapture(store, List.of(checkpointBlock, childInEpoch1, childInEpoch2));

    assertThat(update.getHotStates())
        .containsExactly(Map.entry(checkpointBlock.getRoot(), checkpointBlock.getState()));
    assertThat(update.getHotStateNodeInfos())
        .containsExactly(
            Map.entry(
                checkpointBlock.getRoot(),
                new HotStateNodeInfo(UInt64.ONE, UInt64.valueOf(2), List.of(genesis.getRoot()))));
  }

  @Test
  void diffMode_ancestorsEndWithFinalizedRoot() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    chainBuilder.generateBlocksUpToSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(4)));
    importOneBlockPerTransaction(store, 1, 24);
    final SignedBlockAndState finalizedBlock = chainBuilder.getBlockAndStateAtSlot(16);
    final UpdatableStore.StoreTransaction finalizeTx =
        store.startTransaction(capturingChannel(new ArrayList<>()));
    finalizeTx.setFinalizedCheckpoint(
        new Checkpoint(UInt64.valueOf(2), finalizedBlock.getRoot()), false);
    assertThat(finalizeTx.commit()).isCompleted();

    final List<StorageUpdate> updates = importOneBlockPerTransaction(store, 25, 32);

    final SignedBlockAndState checkpointBlock = chainBuilder.getBlockAndStateAtSlot(32);
    final HotStateNodeInfo nodeInfo =
        updates.getLast().getHotStateNodeInfos().get(checkpointBlock.getRoot());
    // The checkpoint blocks above the finalized block, then the finalized block itself; the
    // walk stops there although fork choice still holds older blocks
    assertThat(nodeInfo.ancestorCheckpointRoots())
        .containsExactly(
            chainBuilder.getBlockAtSlot(31).getRoot(),
            chainBuilder.getBlockAtSlot(24).getRoot(),
            chainBuilder.getBlockAtSlot(23).getRoot(),
            finalizedBlock.getRoot());
  }

  @Test
  void diffMode_skipsWhenParentStateNotCached() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    store.startMetrics();
    chainBuilder.generateBlocksUpToSlot(7);
    final SignedBlockAndState firstBlockOfEpoch = chainBuilder.generateBlockAtSlot(9);
    importOneBlockPerTransaction(store, 1, 7);
    final SignedBlockAndState checkpointBlock = chainBuilder.getBlockAndStateAtSlot(7);
    // Evicted from every in-memory cache, but still in fork choice
    ((Store) store).removeBlockAndState(checkpointBlock.getRoot());
    assertThat(store.getBlockStateIfAvailable(checkpointBlock.getRoot())).isEmpty();

    final StorageUpdate update = commitAndCapture(store, List.of(firstBlockOfEpoch));

    assertThat(update.getHotBlocks()).containsKey(firstBlockOfEpoch.getRoot());
    assertThat(update.getHotStates()).isEmpty();
    assertThat(update.getHotStateNodeInfos()).isEmpty();
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "store_state_diff_nodes_skipped_total"))
        .isEqualTo(1);
  }

  @Test
  void diffMode_usesEpochStatesWhenBlockStateEvicted() {
    final UpdatableStore store = createGenesisStore(diffModeConfig());
    chainBuilder.generateBlocksUpToSlot(7);
    final SignedBlockAndState firstBlockOfEpoch = chainBuilder.generateBlockAtSlot(9);
    importOneBlockPerTransaction(store, 1, 7);
    final SignedBlockAndState checkpointBlock = chainBuilder.getBlockAndStateAtSlot(7);
    ((Store) store).removeBlockAndState(checkpointBlock.getRoot());
    // The pre-epoch-transition parent state kept by the epoch states cache ("ADD.PRE")
    ((Store) store).getEpochStates().orElseThrow().put(checkpointBlock.getRoot(), checkpointBlock);

    final StorageUpdate update = commitAndCapture(store, List.of(firstBlockOfEpoch));

    assertThat(update.getHotStates())
        .containsExactly(Map.entry(checkpointBlock.getRoot(), checkpointBlock.getState()));
    assertThat(update.getHotStateNodeInfos()).containsKey(checkpointBlock.getRoot());
  }

  @Test
  void legacyMode_unchanged() {
    final UpdatableStore store = createGenesisStore(StoreConfig.createDefault());
    chainBuilder.generateBlocksUpToSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(4)));

    final List<StorageUpdate> updates = importOneBlockPerTransaction(store, 1, 32);

    // The first block of every second epoch, without node infos
    for (int slot = 1; slot <= 32; slot++) {
      final StorageUpdate update = updates.get(slot - 1);
      assertThat(update.getHotStateNodeInfos()).isEmpty();
      final SignedBlockAndState block = chainBuilder.getBlockAndStateAtSlot(slot);
      if (slot == 16 || slot == 32) {
        assertThat(update.getHotStates())
            .containsExactly(Map.entry(block.getRoot(), block.getState()));
      } else {
        assertThat(update.getHotStates()).isEmpty();
      }
    }
  }

  private static StoreConfig diffModeConfig() {
    return StoreConfig.builder().stateDiffsEnabled(true).build();
  }

  private UpdatableStore createGenesisStore(final StoreConfig storeConfig) {
    final SignedBlockAndState genesis = chainBuilder.generateGenesis();
    final Checkpoint genesisCheckpoint = chainBuilder.getCurrentCheckpointForEpoch(0);
    return StoreBuilder.create()
        .asyncRunner(SYNC_RUNNER)
        .metricsSystem(metricsSystem)
        .specProvider(spec)
        .blockProvider(roots -> SafeFuture.completedFuture(Collections.emptyMap()))
        .earliestBlobSidecarSlotProvider(EarliestBlobSidecarSlotProvider.NOOP)
        .stateProvider(StateAndBlockSummaryProvider.NOOP)
        .anchor(Optional.empty())
        .genesisTime(genesis.getState().getGenesisTime())
        .time(genesis.getState().getGenesisTime())
        .latestFinalized(AnchorPoint.create(spec, genesisCheckpoint, genesis))
        .justifiedCheckpoint(genesisCheckpoint)
        .bestJustifiedCheckpoint(genesisCheckpoint)
        .blockInformation(
            Map.of(
                genesis.getRoot(),
                new StoredBlockMetadata(
                    genesis.getSlot(),
                    genesis.getRoot(),
                    genesis.getParentRoot(),
                    genesis.getStateRoot(),
                    genesis.getExecutionBlockNumber(),
                    genesis.getExecutionBlockHash(),
                    Optional.of(spec.calculateBlockCheckpoints(genesis.getState())))))
        .storeConfig(storeConfig)
        .votes(Collections.emptyMap())
        .latestCanonicalBlockRoot(Optional.empty())
        .build();
  }

  /** Imports the chain builder blocks at slots {@code from..to}, one transaction each. */
  private List<StorageUpdate> importOneBlockPerTransaction(
      final UpdatableStore store, final long from, final long to) {
    return chainBuilder
        .streamBlocksAndStates(from, to)
        .map(block -> commitAndCapture(store, List.of(block)))
        .toList();
  }

  private StorageUpdate commitAndCapture(
      final UpdatableStore store, final List<SignedBlockAndState> blocks) {
    final List<StorageUpdate> updates = new ArrayList<>();
    final UpdatableStore.StoreTransaction tx = store.startTransaction(capturingChannel(updates));
    blocks.forEach(
        block -> tx.putBlockAndState(block, spec.calculateBlockCheckpoints(block.getState())));
    assertThat(tx.commit()).isCompleted();
    assertThat(updates).hasSize(1);
    return updates.getFirst();
  }

  private static StorageUpdateChannel capturingChannel(final List<StorageUpdate> updates) {
    final StorageUpdateChannel channel = mock(StorageUpdateChannel.class);
    when(channel.onStorageUpdate(any()))
        .thenAnswer(
            invocation -> {
              updates.add(invocation.getArgument(0));
              return SafeFuture.completedFuture(UpdateResult.EMPTY);
            });
    return channel;
  }
}
