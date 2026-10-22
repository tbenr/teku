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

import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockSummary;
import tech.pegasys.teku.spec.datastructures.blocks.BlockAndCheckpoints;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedBlindedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.api.FinalizedChainData;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.protoarray.BlockMetadataStore.HaltableBeaconBlockProcessor;
import tech.pegasys.teku.storage.protoarray.ExecutionPayloadUpdate;
import tech.pegasys.teku.storage.protoarray.ForkChoiceStrategy;

class StoreTransactionUpdatesFactory {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final Store baseStore;
  private final StoreTransaction tx;

  private final Map<Bytes32, BlockAndCheckpoints> hotBlocks;
  private final Map<Bytes32, SignedBlockAndState> hotBlockAndStates;
  private final Map<SlotAndBlockRoot, List<BlobSidecar>> blobSidecars;
  private final Optional<UInt64> maybeEarliestBlobSidecarSlot;
  private final Optional<Bytes32> maybeLatestCanonicalBlockRoot;
  private final Optional<UInt64> maybeCustodyGroupCount;
  private final Map<Bytes32, SlotAndBlockRoot> stateRoots;
  private final AnchorPoint latestFinalized;
  private final Map<Bytes32, UInt64> prunedHotBlockRoots = new ConcurrentHashMap<>();
  private final Map<Bytes32, ExecutionPayloadUpdate> hotExecutionPayloads;

  public StoreTransactionUpdatesFactory(
      final Spec spec,
      final Store baseStore,
      final StoreTransaction tx,
      final AnchorPoint latestFinalized) {
    this.spec = spec;
    this.baseStore = baseStore;
    this.tx = tx;
    this.latestFinalized = latestFinalized;
    // Save copy of tx data that may be pruned
    hotBlocks =
        tx.blockData.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, entry -> entry.getValue().toBlockAndCheckpoints()));
    hotBlockAndStates = new ConcurrentHashMap<>(tx.blockData);
    stateRoots = new ConcurrentHashMap<>(tx.stateRoots);
    blobSidecars = new ConcurrentHashMap<>(tx.blobSidecars);
    maybeEarliestBlobSidecarSlot = tx.maybeEarliestBlobSidecarTransactionSlot;
    maybeLatestCanonicalBlockRoot = tx.maybeLatestCanonicalBlockRoot;
    maybeCustodyGroupCount = tx.maybeCustodyGroupCount;
    hotExecutionPayloads = new ConcurrentHashMap<>(tx.executionPayloadData);
  }

  public static StoreTransactionUpdates create(
      final Spec spec,
      final Store baseStore,
      final StoreTransaction tx,
      final AnchorPoint latestFinalized) {
    return new StoreTransactionUpdatesFactory(spec, baseStore, tx, latestFinalized).build();
  }

  public StoreTransactionUpdates build() {
    // If a new checkpoint has been finalized, calculated what to finalize and what to prune
    final Checkpoint prevFinalizedCheckpoint = baseStore.getFinalizedCheckpoint();
    final Optional<Checkpoint> newFinalizedCheckpoint =
        Optional.of(tx.getFinalizedCheckpoint())
            .filter(c -> c.getEpoch().compareTo(prevFinalizedCheckpoint.getEpoch()) > 0);

    return newFinalizedCheckpoint
        .map(this::buildFinalizedUpdates)
        .orElseGet(
            () ->
                createStoreTransactionUpdates(
                    Optional.empty(),
                    tx.clearFinalizedOptimisticTransitionPayload,
                    Optional.empty(),
                    createBlindedExecutionPayloads()));
  }

  private StoreTransactionUpdates buildFinalizedUpdates(final Checkpoint finalizedCheckpoint) {
    final Map<Bytes32, Bytes32> finalizedChildToParent =
        collectFinalizedRoots(latestFinalized.getRoot());
    final Set<SignedBeaconBlock> finalizedBlocks =
        collectFinalizedBlocks(tx, finalizedChildToParent);
    final Map<Bytes32, BeaconState> finalizedStates =
        collectFinalizedStates(tx, finalizedChildToParent);
    // Collect blinded execution payloads before pruning
    final Map<Bytes32, SignedBlindedExecutionPayloadEnvelope> blindedExecutionPayloads =
        createBlindedExecutionPayloads();
    final FinalizedChainData.Builder finalizedChainDataBuilder = FinalizedChainData.builder();
    createFinalizedExecutionPayloadBoundaryBlock()
        .ifPresent(finalizedChainDataBuilder::finalizedExecutionPayloadBoundaryBlock);
    final boolean optimisticTransitionBlockRootSet;
    final Optional<Bytes32> optimisticTransitionBlockRoot;
    if (tx.clearFinalizedOptimisticTransitionPayload) {
      optimisticTransitionBlockRootSet = true;
      optimisticTransitionBlockRoot = Optional.empty();
    } else if (!spec.isMergeTransitionComplete(baseStore.getLatestFinalized().getState())
        && spec.isMergeTransitionComplete(latestFinalized.getState())) {
      // Transition block was finalized by this transaction
      optimisticTransitionBlockRootSet = true;
      optimisticTransitionBlockRoot =
          baseStore
              .getForkChoiceStrategy()
              .getOptimisticallySyncedTransitionBlockRoot(latestFinalized.getRoot());
    } else {
      optimisticTransitionBlockRootSet = false;
      optimisticTransitionBlockRoot = Optional.empty();
    }

    // Prune collections
    calculatePrunedHotBlockRoots();
    prunedHotBlockRoots
        .keySet()
        .forEach(
            blockRoot -> {
              hotBlocks.remove(blockRoot);
              hotBlockAndStates.remove(blockRoot);
              hotExecutionPayloads.remove(blockRoot);
            });

    final Optional<FinalizedChainData> finalizedChainData =
        Optional.of(
            finalizedChainDataBuilder
                .latestFinalized(latestFinalized)
                .finalizedChildAndParent(finalizedChildToParent)
                .finalizedBlocks(finalizedBlocks)
                .finalizedStates(finalizedStates)
                .build());

    return createStoreTransactionUpdates(
        finalizedChainData,
        optimisticTransitionBlockRootSet,
        optimisticTransitionBlockRoot,
        blindedExecutionPayloads);
  }

  /** Pull subset of hot states that sit at epoch boundaries to persist */
  private HotStatesToPersist getHotStatesToPersist() {
    return baseStore.isStateDiffMode()
        ? getCheckpointBlockStatesToPersist()
        : new HotStatesToPersist(getEpochBoundaryStatesToPersist(), Collections.emptyMap());
  }

  private Map<Bytes32, BeaconState> getEpochBoundaryStatesToPersist() {
    final Map<Bytes32, BeaconState> statesToPersist =
        hotBlockAndStates.entrySet().stream()
            .filter(
                e ->
                    baseStore.shouldPersistState(
                        e.getValue().getSlot(), blockSlot(e.getValue().getParentRoot())))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getState()));
    if (statesToPersist.size() > 0) {
      LOG.trace("Persist {} hot states", statesToPersist.size());
    }
    return statesToPersist;
  }

  /**
   * State diff mode: the states of the checkpoint blocks made known by the transaction's blocks,
   * with where each sits in the chain. For every block {@code B} of the transaction whose epoch is
   * greater than its parent {@code R}'s:
   *
   * <ul>
   *   <li>on its epoch's start slot, {@code B} is the checkpoint block of its own epoch, and {@code
   *       R} of the empty epochs in between, if any;
   *   <li>otherwise {@code R} is the checkpoint block of the epochs after its own up to {@code
   *       B}'s.
   * </ul>
   *
   * <p>{@code R} on an epoch start slot also serves its own epoch, but that was recorded when
   * {@code R} itself was imported; the write for an existing node only raises its last epoch.
   */
  private HotStatesToPersist getCheckpointBlockStatesToPersist() {
    final Map<Bytes32, ServedEpochs> checkpointBlocks = new HashMap<>();
    hotBlockAndStates
        .values()
        .forEach(
            block -> {
              final Bytes32 parentRoot = block.getParentRoot();
              final UInt64 blockEpoch = spec.computeEpochAtSlot(block.getSlot());
              final Optional<UInt64> maybeParentSlot = blockSlot(parentRoot);
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

    final Map<Bytes32, BeaconState> states = new HashMap<>();
    final Map<Bytes32, HotStateNodeInfo> nodeInfos = new HashMap<>();
    checkpointBlocks.forEach(
        (checkpointRoot, servedEpochs) -> {
          final Optional<BeaconState> state = getCheckpointBlockState(checkpointRoot);
          if (state.isEmpty()) {
            LOG.warn(
                "Not storing a state diff node for checkpoint block {} at slot {} serving epochs"
                    + " {}..{}: its state is no longer in memory",
                checkpointRoot,
                servedEpochs.slot(),
                servedEpochs.firstEpoch(),
                servedEpochs.lastEpoch());
            baseStore.onStateDiffNodeSkipped();
            return;
          }
          states.put(checkpointRoot, state.get());
          nodeInfos.put(
              checkpointRoot,
              new HotStateNodeInfo(
                  servedEpochs.firstEpoch(),
                  servedEpochs.lastEpoch(),
                  collectAncestorCheckpointRoots(checkpointRoot)));
        });
    if (!states.isEmpty()) {
      LOG.trace("Persist {} checkpoint block states as state diff nodes", states.size());
    }
    return new HotStatesToPersist(states, nodeInfos);
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

  private boolean isEpochStartSlot(final UInt64 slot) {
    return spec.computeStartSlotAtEpoch(spec.computeEpochAtSlot(slot)).equals(slot);
  }

  private Optional<BeaconState> getCheckpointBlockState(final Bytes32 checkpointRoot) {
    // The epoch states cache, consulted by the store in state diff mode, normally holds it
    final Optional<BeaconState> state = tx.getBlockStateIfAvailable(checkpointRoot);
    if (state.isPresent()) {
      return state;
    }
    // The finalized anchor always has a node; only its served epochs may need extending
    return Stream.of(latestFinalized, baseStore.getLatestFinalized())
        .filter(anchor -> anchor.getRoot().equals(checkpointRoot))
        .map(AnchorPoint::getState)
        .findFirst();
  }

  /**
   * The checkpoint blocks among the ancestors of {@code checkpointRoot}, nearest first, ending with
   * the finalized block of the store this transaction applies to. An ancestor is a checkpoint block
   * when it sits on an epoch start slot or its child is in a later epoch. That finalized block
   * always has a node, so the list always resolves to a previous node, while the ones above it are
   * the nodes that may exist.
   */
  private List<Bytes32> collectAncestorCheckpointRoots(final Bytes32 checkpointRoot) {
    final Bytes32 finalizedRoot = baseStore.getLatestFinalized().getRoot();
    final List<Bytes32> ancestorRoots = new ArrayList<>();
    // The epoch of the previously visited block, the child of the next one; empty before the start
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
    SignedBlockAndState txBlock = tx.blockData.get(current);
    while (txBlock != null) {
      if (!visitor.process(current, txBlock.getSlot(), txBlock.getParentRoot())) {
        break;
      }
      current = txBlock.getParentRoot();
      txBlock = tx.blockData.get(current);
    }
    final ForkChoiceStrategy forkChoiceStrategy = baseStore.getForkChoiceStrategy();
    if (txBlock == null && !current.equals(finalizedRoot) && forkChoiceStrategy.contains(current)) {
      forkChoiceStrategy.processBeaconBlockChainWhile(current, visitor);
    }
    ancestorRoots.add(finalizedRoot);
    return ancestorRoots;
  }

  /** The epochs a checkpoint block at {@code slot} is known to serve. */
  private record ServedEpochs(UInt64 slot, UInt64 firstEpoch, UInt64 lastEpoch) {}

  /** The hot states to persist and, in state diff mode, where each sits in the chain. */
  private record HotStatesToPersist(
      Map<Bytes32, BeaconState> states, Map<Bytes32, HotStateNodeInfo> nodeInfos) {}

  private Optional<UInt64> blockSlot(final Bytes32 root) {
    return Optional.ofNullable(hotBlockAndStates.get(root))
        .map(SignedBlockAndState::getSlot)
        .or(() -> baseStore.getForkChoiceStrategy().blockSlot(root));
  }

  private Map<Bytes32, Bytes32> collectFinalizedRoots(final Bytes32 newlyFinalizedBlockRoot) {
    final HashMap<Bytes32, Bytes32> childToParent = new HashMap<>();
    // Add any new blocks that were immediately finalized
    Bytes32 finalizedChainHeadRoot = newlyFinalizedBlockRoot;
    while (!baseStore.containsBlock(finalizedChainHeadRoot)) {
      // Blocks from the new transaction must all be in memory as they haven't been stored yet
      final SignedBeaconBlock block = hotBlocks.get(finalizedChainHeadRoot).getBlock();
      childToParent.put(finalizedChainHeadRoot, block.getParentRoot());
      finalizedChainHeadRoot = block.getParentRoot();
    }

    // Add existing hot blocks that are now finalized
    if (baseStore.getForkChoiceStrategy().contains(finalizedChainHeadRoot)) {
      baseStore
          .getForkChoiceStrategy()
          .processBeaconBlockChain(
              finalizedChainHeadRoot,
              (blockRoot, slot, parentRoot) -> childToParent.put(blockRoot, parentRoot));
    }
    return childToParent;
  }

  private Set<SignedBeaconBlock> collectFinalizedBlocks(
      final StoreTransaction tx, final Map<Bytes32, Bytes32> finalizedChildToParent) {
    return finalizedChildToParent.keySet().stream()
        .flatMap(root -> tx.getBlockIfAvailable(root).stream())
        .collect(Collectors.toSet());
  }

  private Map<Bytes32, BeaconState> collectFinalizedStates(
      final StoreTransaction tx, final Map<Bytes32, Bytes32> finalizedChildToParent) {
    final Map<Bytes32, BeaconState> states = new HashMap<>();
    for (Bytes32 finalizedRoot : finalizedChildToParent.keySet()) {
      tx.getBlockStateIfAvailable(finalizedRoot)
          .ifPresent(state -> states.put(finalizedRoot, state));
    }
    return states;
  }

  private boolean shouldPrune(
      final BeaconBlockSummary finalizedBlock,
      final Bytes32 blockRoot,
      final UInt64 slot,
      final Bytes32 parentRoot) {
    return (slot.isLessThanOrEqualTo(finalizedBlock.getSlot())
            || prunedHotBlockRoots.containsKey(parentRoot))
        // Keep the actual finalized block
        && !blockRoot.equals(finalizedBlock.getRoot());
  }

  private void calculatePrunedHotBlockRoots() {
    final BeaconBlockSummary finalizedBlock = tx.getLatestFinalized().getBlockSummary();
    baseStore
        .getForkChoiceStrategy()
        .processAllBeaconBlocksInOrder(
            (blockRoot, slot, parentRoot) -> {
              if (shouldPrune(finalizedBlock, blockRoot, slot, parentRoot)) {
                prunedHotBlockRoots.put(blockRoot, slot);
              }
            });

    tx.blockData.values().stream()
        // Iterate new blocks in slot order to guarantee we see parents first
        .sorted(Comparator.comparing(SignedBlockAndState::getSlot))
        .filter(
            newBlockAndState ->
                shouldPrune(
                    finalizedBlock,
                    newBlockAndState.getRoot(),
                    newBlockAndState.getSlot(),
                    newBlockAndState.getParentRoot()))
        .forEach(
            newBlockAndState ->
                prunedHotBlockRoots.put(newBlockAndState.getRoot(), newBlockAndState.getSlot()));
  }

  private StoreTransactionUpdates createStoreTransactionUpdates(
      final Optional<FinalizedChainData> finalizedChainData,
      final boolean optimisticTransitionBlockRootSet,
      final Optional<Bytes32> optimisticTransitionBlockRoot,
      final Map<Bytes32, SignedBlindedExecutionPayloadEnvelope> blindedExecutionPayloads) {
    final HotStatesToPersist hotStatesToPersist = getHotStatesToPersist();
    return new StoreTransactionUpdates(
        tx,
        finalizedChainData,
        hotBlocks,
        hotBlockAndStates,
        hotStatesToPersist.states(),
        hotStatesToPersist.nodeInfos(),
        blobSidecars,
        maybeEarliestBlobSidecarSlot,
        prunedHotBlockRoots,
        stateRoots,
        optimisticTransitionBlockRootSet,
        optimisticTransitionBlockRoot,
        maybeLatestCanonicalBlockRoot,
        maybeCustodyGroupCount,
        spec.supportsBlobSidecars(),
        spec.supportsDataColumnSidecars(),
        spec.supportsExecutionPayloadEnvelopes(),
        hotExecutionPayloads,
        blindedExecutionPayloads);
  }

  private Map<Bytes32, SignedBlindedExecutionPayloadEnvelope> createBlindedExecutionPayloads() {
    return hotExecutionPayloads.entrySet().stream()
        .map(
            entry -> {
              final SignedExecutionPayloadEnvelope executionPayload =
                  entry.getValue().executionPayload();
              return Map.entry(entry.getKey(), executionPayload.blind(spec));
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Optional<BlockAndCheckpoints> createFinalizedExecutionPayloadBoundaryBlock() {
    if (!spec.atSlot(latestFinalized.getBlockSlot())
        .getMilestone()
        .isGreaterThanOrEqualTo(SpecMilestone.GLOAS)) {
      return Optional.empty();
    }
    return Optional.ofNullable(hotBlocks.get(latestFinalized.getRoot()))
        .map(
            boundaryBlock ->
                baseStore
                    .getForkChoiceStrategy()
                    .getBlockData(boundaryBlock.getRoot(), PAYLOAD_STATUS_PENDING)
                    .map(
                        pendingNode ->
                            new BlockAndCheckpoints(
                                boundaryBlock.getBlock(),
                                boundaryBlock.getBlockCheckpoints(),
                                Optional.of(pendingNode.getExecutionBlockNumber()),
                                Optional.of(pendingNode.getExecutionGasLimit())))
                    .orElse(boundaryBlock));
  }
}
