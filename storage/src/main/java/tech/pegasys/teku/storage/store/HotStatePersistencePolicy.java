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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.StateAndBlockSummary;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.api.HotState;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.protoarray.BlockMetadataStore.HaltableBeaconBlockProcessor;
import tech.pegasys.teku.storage.protoarray.ForkChoiceStrategy;

/**
 * Which hot states the store persists, and which persisted state it regenerates other states from.
 *
 * <ul>
 *   <li>{@link #everyNthEpoch(Spec, int)}: the post-state of the first block of every n-th epoch,
 *       persisted whole.
 *   <li>{@link #checkpointBlocks(Spec)}: the post-state of every epoch's checkpoint block,
 *       persisted as a state diff DAG node with its place in the DAG. A block on its epoch's start
 *       slot is the checkpoint block of its own epoch; the parent of the first block of an epoch is
 *       the checkpoint block of the epochs after its own up to that block's, except the block's own
 *       epoch when the block sits on its start slot.
 * </ul>
 */
abstract class HotStatePersistencePolicy {

  protected final Spec spec;

  private HotStatePersistencePolicy(final Spec spec) {
    this.spec = spec;
  }

  /** Persists the post-state of the first block of every {@code epochs}-th epoch; 0 for none. */
  static HotStatePersistencePolicy everyNthEpoch(final Spec spec, final int epochs) {
    return new EveryNthEpoch(spec, epochs);
  }

  /** Persists the post-state of every epoch's checkpoint block as a state diff DAG node. */
  static HotStatePersistencePolicy checkpointBlocks(final Spec spec) {
    return new CheckpointBlocks(spec);
  }

  /** Whether {@code slot} is the first slot of its epoch. */
  final boolean isEpochStartSlot(final UInt64 slot) {
    return spec.computeStartSlotAtEpoch(spec.computeEpochAtSlot(slot)).equals(slot);
  }

  /** Whether a block at {@code blockSlot} whose parent is at {@code parentSlot} is persisted. */
  protected final boolean isFirstBlockOfNthEpoch(
      final UInt64 blockSlot, final Optional<UInt64> parentSlot, final int epochs) {
    return epochs > 0
        && parentSlot
            .map(
                slot ->
                    spec.getGenesisSpec()
                        .miscHelpers()
                        .isSlotAtNthEpochBoundary(blockSlot, slot, epochs))
            .orElse(false);
  }

  /** Registers the metrics of this policy. */
  void startMetrics(final MetricsSystem metricsSystem) {}

  /**
   * The post-state of {@code blockRoot} if it is in memory.
   *
   * @param blockStates the block states cache
   * @param epochStates the epoch states cache, if enabled
   */
  abstract Optional<BeaconState> getBlockStateIfAvailable(
      Bytes32 blockRoot,
      Function<Bytes32, Optional<StateAndBlockSummary>> blockStates,
      Optional<Map<Bytes32, StateAndBlockSummary>> epochStates);

  /** The hot states the transaction makes due for persisting, by block root. */
  abstract Map<Bytes32, HotState> selectStatesToPersist(TransactionView transaction);

  /**
   * The block whose persisted state can serve as the regeneration base of the block at {@code
   * slot}, its descendants included, if the block makes one known: walking back from a target
   * block, the first block for which this is present gives the nearest persisted base.
   *
   * @param blockSlot the slot of a block known to the store
   */
  abstract Optional<SlotAndBlockRoot> getPersistedBase(
      UInt64 slot, Bytes32 root, Bytes32 parentRoot, Function<Bytes32, Optional<UInt64>> blockSlot);

  /**
   * How many epochs before the persisted base a closer in-memory state may be, and still be
   * preferred to loading the persisted base.
   */
  abstract int getReplayToleranceInEpochs();

  /** What the policy reads from a store transaction to select the hot states to persist. */
  interface TransactionView {

    /** The blocks the transaction adds that it does not prune. */
    Collection<SignedBlockAndState> blocks();

    /** The slot of a block of the transaction or of the store. */
    Optional<UInt64> blockSlot(Bytes32 root);

    /** A block of the transaction, including the blocks it prunes. */
    Optional<SignedBlockAndState> transactionBlock(Bytes32 root);

    ForkChoiceStrategy forkChoiceStrategy();

    /** The finalized block root of the store the transaction applies to. */
    Bytes32 finalizedRoot();

    /** The post-state of a block of the transaction or of the store, if still in memory. */
    Optional<BeaconState> blockState(Bytes32 root);
  }

  private static class EveryNthEpoch extends HotStatePersistencePolicy {
    private static final Logger LOG = LogManager.getLogger();

    private final int epochs;

    private EveryNthEpoch(final Spec spec, final int epochs) {
      super(spec);
      this.epochs = epochs;
    }

    @Override
    Optional<BeaconState> getBlockStateIfAvailable(
        final Bytes32 blockRoot,
        final Function<Bytes32, Optional<StateAndBlockSummary>> blockStates,
        final Optional<Map<Bytes32, StateAndBlockSummary>> epochStates) {
      return blockStates.apply(blockRoot).map(StateAndBlockSummary::getState);
    }

    @Override
    Map<Bytes32, HotState> selectStatesToPersist(final TransactionView transaction) {
      final Map<Bytes32, HotState> statesToPersist =
          transaction.blocks().stream()
              .filter(
                  block ->
                      isFirstBlockOfNthEpoch(
                          block.getSlot(), transaction.blockSlot(block.getParentRoot()), epochs))
              .collect(
                  Collectors.toMap(
                      SignedBlockAndState::getRoot,
                      block -> HotState.withoutNodeInfo(block.getState())));
      if (!statesToPersist.isEmpty()) {
        LOG.trace("Persist {} hot states", statesToPersist.size());
      }
      return statesToPersist;
    }

    @Override
    Optional<SlotAndBlockRoot> getPersistedBase(
        final UInt64 slot,
        final Bytes32 root,
        final Bytes32 parentRoot,
        final Function<Bytes32, Optional<UInt64>> blockSlot) {
      if (isFirstBlockOfNthEpoch(slot, blockSlot.apply(parentRoot), epochs)) {
        return Optional.of(new SlotAndBlockRoot(slot, root));
      }
      return Optional.empty();
    }

    @Override
    int getReplayToleranceInEpochs() {
      return epochs;
    }

    @Override
    public String toString() {
      return "every " + epochs + " epochs";
    }
  }

  private static class CheckpointBlocks extends HotStatePersistencePolicy {
    private static final Logger LOG = LogManager.getLogger();

    private Optional<Counter> skippedCounter = Optional.empty();

    private CheckpointBlocks(final Spec spec) {
      super(spec);
    }

    @Override
    void startMetrics(final MetricsSystem metricsSystem) {
      skippedCounter =
          Optional.of(
              metricsSystem.createCounter(
                  TekuMetricCategory.STORAGE,
                  "store_state_diff_nodes_skipped_total",
                  "Checkpoint block states not stored as state diff nodes because they were no"
                      + " longer in memory"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The epoch states cache is consulted too: it keeps the pre-epoch-transition parent states,
     * which are the checkpoint block states persisted as state diff nodes.
     */
    @Override
    Optional<BeaconState> getBlockStateIfAvailable(
        final Bytes32 blockRoot,
        final Function<Bytes32, Optional<StateAndBlockSummary>> blockStates,
        final Optional<Map<Bytes32, StateAndBlockSummary>> epochStates) {
      return blockStates
          .apply(blockRoot)
          .or(() -> epochStates.flatMap(states -> Optional.ofNullable(states.get(blockRoot))))
          .map(StateAndBlockSummary::getState);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The states of the checkpoint blocks made known by the transaction's blocks, with where
     * each sits in the chain. For every block {@code B} of the transaction whose epoch is greater
     * than its parent {@code R}'s:
     *
     * <ul>
     *   <li>on its epoch's start slot, {@code B} is the checkpoint block of its own epoch, and
     *       {@code R} of the empty epochs in between, if any;
     *   <li>otherwise {@code R} is the checkpoint block of the epochs after its own up to {@code
     *       B}'s.
     * </ul>
     *
     * <p>{@code R} on an epoch start slot also serves its own epoch, but that was recorded when
     * {@code R} itself was imported; the write for an existing node only raises its last epoch.
     */
    @Override
    Map<Bytes32, HotState> selectStatesToPersist(final TransactionView transaction) {
      final Map<Bytes32, ServedEpochs> checkpointBlocks = new HashMap<>();
      transaction
          .blocks()
          .forEach(
              block -> {
                final Bytes32 parentRoot = block.getParentRoot();
                final UInt64 blockEpoch = spec.computeEpochAtSlot(block.getSlot());
                final Optional<UInt64> maybeParentSlot = transaction.blockSlot(parentRoot);
                if (maybeParentSlot.isEmpty()) {
                  return;
                }
                final UInt64 parentSlot = maybeParentSlot.get();
                final UInt64 parentEpoch = spec.computeEpochAtSlot(parentSlot);
                if (!blockEpoch.isGreaterThan(parentEpoch)) {
                  return;
                }
                final UInt64 parentLastEpoch;
                if (isEpochStartSlot(block.getSlot())) {
                  addServedEpochs(
                      checkpointBlocks,
                      block.getRoot(),
                      new ServedEpochs(block.getSlot(), blockEpoch, blockEpoch));
                  parentLastEpoch = blockEpoch.minus(1);
                } else {
                  parentLastEpoch = blockEpoch;
                }
                final UInt64 parentFirstEpoch = parentEpoch.plus(1);
                if (parentLastEpoch.isGreaterThanOrEqualTo(parentFirstEpoch)) {
                  addServedEpochs(
                      checkpointBlocks,
                      parentRoot,
                      new ServedEpochs(parentSlot, parentFirstEpoch, parentLastEpoch));
                }
              });

      final Map<Bytes32, HotState> states = new HashMap<>();
      checkpointBlocks.forEach(
          (checkpointRoot, servedEpochs) -> {
            final Optional<BeaconState> state = transaction.blockState(checkpointRoot);
            if (state.isEmpty()) {
              LOG.warn(
                  "Not storing a state diff node for checkpoint block {} at slot {} serving epochs"
                      + " {}..{}: its state is no longer in memory",
                  checkpointRoot,
                  servedEpochs.slot(),
                  servedEpochs.firstEpoch(),
                  servedEpochs.lastEpoch());
              skippedCounter.ifPresent(Counter::inc);
              return;
            }
            states.put(
                checkpointRoot,
                HotState.withNodeInfo(
                    state.get(),
                    new HotStateNodeInfo(
                        servedEpochs.firstEpoch(),
                        servedEpochs.lastEpoch(),
                        collectAncestorCheckpointRoots(transaction, checkpointRoot))));
          });
      if (!states.isEmpty()) {
        LOG.trace("Persist {} checkpoint block states as state diff nodes", states.size());
      }
      return states;
    }

    private static void addServedEpochs(
        final Map<Bytes32, ServedEpochs> checkpointBlocks,
        final Bytes32 root,
        final ServedEpochs servedEpochs) {
      checkpointBlocks.merge(
          root,
          servedEpochs,
          (a, b) ->
              new ServedEpochs(
                  a.slot(), a.firstEpoch().min(b.firstEpoch()), a.lastEpoch().max(b.lastEpoch())));
    }

    /**
     * The checkpoint blocks among the ancestors of {@code checkpointRoot}, nearest first, ending
     * with the finalized block of the store the transaction applies to. An ancestor is a checkpoint
     * block when it sits on an epoch start slot or its child is in a later epoch. That finalized
     * block always has a node, so the list always resolves to a previous node, while the ones above
     * it are the nodes that may exist.
     */
    private List<Bytes32> collectAncestorCheckpointRoots(
        final TransactionView transaction, final Bytes32 checkpointRoot) {
      final Bytes32 finalizedRoot = transaction.finalizedRoot();
      final List<Bytes32> ancestorRoots = new ArrayList<>();
      // The epoch of the previously visited block (the child of the next one), empty at first
      final AtomicReference<Optional<UInt64>> childEpoch = new AtomicReference<>(Optional.empty());
      // Returns whether to continue to the parent of the visited block
      final HaltableBeaconBlockProcessor visitor =
          (root, slot, parentRoot) -> {
            if (root.equals(finalizedRoot)) {
              return false;
            }
            final UInt64 epoch = spec.computeEpochAtSlot(slot);
            final boolean isCheckpointBlock =
                isEpochStartSlot(slot) || childEpoch.get().map(epoch::isLessThan).orElse(false);
            if (!root.equals(checkpointRoot) && isCheckpointBlock) {
              ancestorRoots.add(root);
            }
            childEpoch.set(Optional.of(epoch));
            return true;
          };

      // Blocks of this transaction, including those it prunes, are not in fork choice yet
      Bytes32 current = checkpointRoot;
      Optional<SignedBlockAndState> txBlock = transaction.transactionBlock(current);
      while (txBlock.isPresent()) {
        if (!visitor.process(current, txBlock.get().getSlot(), txBlock.get().getParentRoot())) {
          break;
        }
        current = txBlock.get().getParentRoot();
        txBlock = transaction.transactionBlock(current);
      }
      final ForkChoiceStrategy forkChoiceStrategy = transaction.forkChoiceStrategy();
      if (txBlock.isEmpty()
          && !current.equals(finalizedRoot)
          && forkChoiceStrategy.contains(current)) {
        forkChoiceStrategy.processBeaconBlockChainWhile(current, visitor);
      }
      ancestorRoots.add(finalizedRoot);
      return ancestorRoots;
    }

    @Override
    Optional<SlotAndBlockRoot> getPersistedBase(
        final UInt64 slot,
        final Bytes32 root,
        final Bytes32 parentRoot,
        final Function<Bytes32, Optional<UInt64>> blockSlot) {
      if (isEpochStartSlot(slot)) {
        return Optional.of(new SlotAndBlockRoot(slot, root));
      }
      final Optional<UInt64> parentSlot = blockSlot.apply(parentRoot);
      if (isFirstBlockOfNthEpoch(slot, parentSlot, 1)) {
        return parentSlot.map(slotOfParent -> new SlotAndBlockRoot(slotOfParent, parentRoot));
      }
      return Optional.empty();
    }

    @Override
    int getReplayToleranceInEpochs() {
      return 1;
    }

    @Override
    public String toString() {
      return "checkpoint blocks";
    }

    /** The epochs a checkpoint block at {@code slot} is known to serve. */
    private record ServedEpochs(UInt64 slot, UInt64 firstEpoch, UInt64 lastEpoch) {}
  }
}
