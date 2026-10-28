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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.StateAndBlockSummary;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.storage.api.HotState;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.protoarray.ForkChoiceStrategy;
import tech.pegasys.teku.storage.store.HotStatePersistencePolicy.TransactionView;

class HotStatePersistencePolicyTest {

  // Minimal spec: 8 slots per epoch
  private final Spec spec = TestSpecFactory.createMinimalDeneb();
  private final ChainBuilder chainBuilder = ChainBuilder.create(spec);
  private final Bytes32 root = Bytes32.fromHexString("0x01");
  private final Bytes32 parentRoot = Bytes32.fromHexString("0x02");

  private final HotStatePersistencePolicy everySecondEpoch =
      HotStatePersistencePolicy.everyNthEpoch(spec, 2);
  private final HotStatePersistencePolicy checkpointBlocks =
      HotStatePersistencePolicy.checkpointBlocks(spec);

  @Test
  void isEpochStartSlot() {
    assertThat(checkpointBlocks.isEpochStartSlot(UInt64.ZERO)).isTrue();
    assertThat(checkpointBlocks.isEpochStartSlot(UInt64.valueOf(16))).isTrue();
    assertThat(everySecondEpoch.isEpochStartSlot(UInt64.valueOf(17))).isFalse();
  }

  @Test
  void everyNthEpoch_persistedBaseIsFirstBlockOfEveryNthEpoch() {
    assertThat(everySecondEpoch.getPersistedBase(slot(16), root, parentRoot, parentAt(15)))
        .contains(new SlotAndBlockRoot(slot(16), root));
    // After an empty first slot, still the first block of the epoch
    assertThat(everySecondEpoch.getPersistedBase(slot(18), root, parentRoot, parentAt(13)))
        .contains(new SlotAndBlockRoot(slot(18), root));
    // Epoch 1 is not a second epoch
    assertThat(everySecondEpoch.getPersistedBase(slot(8), root, parentRoot, parentAt(7))).isEmpty();
    assertThat(everySecondEpoch.getPersistedBase(slot(17), root, parentRoot, parentAt(16)))
        .isEmpty();
    assertThat(everySecondEpoch.getPersistedBase(slot(16), root, parentRoot, unknownParent()))
        .isEmpty();
    assertThat(everySecondEpoch.getReplayToleranceInEpochs()).isEqualTo(2);
  }

  @Test
  void everyNthEpoch_zeroPersistsNothing() {
    final HotStatePersistencePolicy never = HotStatePersistencePolicy.everyNthEpoch(spec, 0);

    assertThat(never.getPersistedBase(slot(16), root, parentRoot, parentAt(15))).isEmpty();
    assertThat(never.getReplayToleranceInEpochs()).isZero();
  }

  @Test
  void checkpointBlocks_blockOnEpochStartSlotIsItsOwnBase() {
    assertThat(checkpointBlocks.getPersistedBase(slot(8), root, parentRoot, parentAt(7)))
        .contains(new SlotAndBlockRoot(slot(8), root));
    // Also when the epochs before it are empty
    assertThat(checkpointBlocks.getPersistedBase(slot(24), root, parentRoot, parentAt(5)))
        .contains(new SlotAndBlockRoot(slot(24), root));
    assertThat(checkpointBlocks.getReplayToleranceInEpochs()).isEqualTo(1);
  }

  @Test
  void checkpointBlocks_parentIsBaseWhenFirstSlotEmpty() {
    assertThat(checkpointBlocks.getPersistedBase(slot(10), root, parentRoot, parentAt(5)))
        .contains(new SlotAndBlockRoot(slot(5), parentRoot));
    assertThat(checkpointBlocks.getPersistedBase(slot(11), root, parentRoot, parentAt(10)))
        .isEmpty();
    assertThat(checkpointBlocks.getPersistedBase(slot(10), root, parentRoot, unknownParent()))
        .isEmpty();
  }

  @Test
  void blockStateLookup_onlyCheckpointBlocksConsultsEpochStates() {
    final SignedBlockAndState block = chainBuilder.generateGenesis();
    final Map<Bytes32, StateAndBlockSummary> epochStates = Map.of(block.getRoot(), block);
    final Function<Bytes32, Optional<StateAndBlockSummary>> noBlockStates = __ -> Optional.empty();

    assertThat(
            checkpointBlocks.getBlockStateIfAvailable(
                block.getRoot(), noBlockStates, Optional.of(epochStates)))
        .contains(block.getState());
    assertThat(
            everySecondEpoch.getBlockStateIfAvailable(
                block.getRoot(), noBlockStates, Optional.of(epochStates)))
        .isEmpty();
    assertThat(
            everySecondEpoch.getBlockStateIfAvailable(
                block.getRoot(), __ -> Optional.of(block), Optional.empty()))
        .contains(block.getState());
  }

  @Test
  void everyNthEpoch_selectsFirstBlockOfEveryNthEpochWithoutNodeInfo() {
    chainBuilder.generateGenesis();
    chainBuilder.generateBlocksUpToSlot(17);
    final StubTransactionView transaction =
        new StubTransactionView(blocksAtSlots(8, 9, 16, 17), Optional.empty());

    assertThat(everySecondEpoch.selectStatesToPersist(transaction))
        .containsExactly(
            Map.entry(
                chainBuilder.getBlockAtSlot(16).getRoot(),
                HotState.withoutNodeInfo(chainBuilder.getStateAtSlot(16))));
    verifyNoInteractions(transaction.forkChoiceStrategy);
  }

  @Test
  void checkpointBlocks_selectsCheckpointBlocksWithNodeInfo() {
    final SignedBlockAndState genesis = chainBuilder.generateGenesis();
    final SignedBlockAndState block3 = chainBuilder.generateBlockAtSlot(3);
    final SignedBlockAndState block8 = chainBuilder.generateBlockAtSlot(8);
    final SignedBlockAndState block21 = chainBuilder.generateBlockAtSlot(21);
    final StubTransactionView transaction =
        new StubTransactionView(List.of(block3, block8, block21), Optional.empty());

    assertThat(checkpointBlocks.selectStatesToPersist(transaction))
        .containsOnly(
            // On the start slot of epoch 1: the checkpoint block of epoch 1, and of epoch 2 whose
            // first slot is empty
            Map.entry(
                block8.getRoot(),
                HotState.withNodeInfo(
                    block8.getState(),
                    new HotStateNodeInfo(
                        UInt64.ONE,
                        UInt64.valueOf(2),
                        List.of(block3.getRoot(), genesis.getRoot())))));
  }

  @Test
  void checkpointBlocks_skipsAndCountsStatesNoLongerInMemory() {
    final StubMetricsSystem metricsSystem = new StubMetricsSystem();
    checkpointBlocks.startMetrics(metricsSystem);
    chainBuilder.generateGenesis();
    final SignedBlockAndState block8 = chainBuilder.generateBlockAtSlot(8);
    final StubTransactionView transaction =
        new StubTransactionView(List.of(block8), Optional.of(block8.getRoot()));

    assertThat(checkpointBlocks.selectStatesToPersist(transaction)).isEmpty();
    assertThat(
            metricsSystem.getCounterValue(
                TekuMetricCategory.STORAGE, "store_state_diff_nodes_skipped_total"))
        .isEqualTo(1);
  }

  private List<SignedBlockAndState> blocksAtSlots(final long... slots) {
    return Arrays.stream(slots).mapToObj(chainBuilder::getBlockAndStateAtSlot).toList();
  }

  private static UInt64 slot(final long slot) {
    return UInt64.valueOf(slot);
  }

  private Function<Bytes32, Optional<UInt64>> parentAt(final long slot) {
    return blockRoot -> blockRoot.equals(parentRoot) ? Optional.of(slot(slot)) : Optional.empty();
  }

  private static Function<Bytes32, Optional<UInt64>> unknownParent() {
    return __ -> Optional.empty();
  }

  /**
   * A transaction holding {@code blocks} on top of the chain builder's genesis, the finalized
   * block, whose states are in memory except the one of {@code evicted}.
   */
  private class StubTransactionView implements TransactionView {
    private final Map<Bytes32, SignedBlockAndState> blocks = new HashMap<>();
    private final Optional<Bytes32> evicted;
    private final ForkChoiceStrategy forkChoiceStrategy = mock(ForkChoiceStrategy.class);

    private StubTransactionView(
        final List<SignedBlockAndState> blocks, final Optional<Bytes32> evicted) {
      blocks.forEach(block -> this.blocks.put(block.getRoot(), block));
      this.evicted = evicted;
    }

    @Override
    public Collection<SignedBlockAndState> blocks() {
      return blocks.values();
    }

    @Override
    public Optional<UInt64> blockSlot(final Bytes32 root) {
      return chainBuilder.getBlock(root).map(SignedBeaconBlock::getSlot);
    }

    @Override
    public Optional<SignedBlockAndState> transactionBlock(final Bytes32 root) {
      return Optional.ofNullable(blocks.get(root));
    }

    @Override
    public ForkChoiceStrategy forkChoiceStrategy() {
      return forkChoiceStrategy;
    }

    @Override
    public Bytes32 finalizedRoot() {
      return chainBuilder.getGenesis().getRoot();
    }

    @Override
    public Optional<BeaconState> blockState(final Bytes32 root) {
      if (evicted.map(root::equals).orElse(false)) {
        return Optional.empty();
      }
      return chainBuilder.getBlockAndState(root).map(SignedBlockAndState::getState);
    }
  }
}
