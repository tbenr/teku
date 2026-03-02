/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.spec.logic.versions.gloas.util;

import static com.google.common.base.Preconditions.checkArgument;
import static tech.pegasys.teku.spec.datastructures.forkchoice.PayloadStatus.PAYLOAD_STATUS_EMPTY;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.gloas.BeaconBlockBodyGloas;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.forkchoice.MutableStore;
import tech.pegasys.teku.spec.datastructures.forkchoice.PayloadStatus;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyForkChoiceStrategy;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyStore;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.statetransition.availability.AvailabilityChecker;
import tech.pegasys.teku.spec.logic.common.util.ForkChoiceUtil;
import tech.pegasys.teku.spec.logic.versions.fulu.util.ForkChoiceUtilFulu;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.BeaconStateAccessorsGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.MiscHelpersGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.statetransition.epoch.EpochProcessorGloas;

public class ForkChoiceUtilGloas extends ForkChoiceUtilFulu {

  public ForkChoiceUtilGloas(
      final SpecConfigGloas specConfig,
      final BeaconStateAccessorsGloas beaconStateAccessors,
      final EpochProcessorGloas epochProcessor,
      final AttestationUtilGloas attestationUtil,
      final MiscHelpersGloas miscHelpers) {
    super(specConfig, beaconStateAccessors, epochProcessor, attestationUtil, miscHelpers);
  }

  public static ForkChoiceUtilGloas required(final ForkChoiceUtil forkChoiceUtil) {
    checkArgument(
        forkChoiceUtil instanceof ForkChoiceUtilGloas,
        "Expected a ForkChoiceUtilGloas but was %s",
        forkChoiceUtil.getClass());
    return (ForkChoiceUtilGloas) forkChoiceUtil;
  }

  public static final int PTC_TIMELINESS_INDEX = 1;

  // From Gloas, there are 3 states available in a given slot
  // pre-state: State at the slot before block applied
  // block-state: State at slot after consensus block applied
  // execution-state: State at slot after consensus and execution has been applied
  // The state to build on for the next slot is the best available of this list
  // (execution-state > block-state > pre-state)
  @Override
  public SafeFuture<Optional<BeaconState>> retrievePreStateRequiredOnBlock(
      final ReadOnlyStore store, final SignedBeaconBlock block) {
    final Bytes32 parentRoot = block.getParentRoot();
    // if the parent root is not in the proto array, no state would be available
    if (!store.containsBlock(parentRoot)) {
      return SafeFuture.completedFuture(Optional.empty());
    }
    final SlotAndBlockRoot slotAndBlockRoot = new SlotAndBlockRoot(block.getSlot(), parentRoot);
    return isParentNodeFull(store, block.getMessage().getBlock())
        .thenCompose(
            isParentNodeFull -> {
              if (isParentNodeFull) {
                return store
                    .retrieveExecutionPayloadState(slotAndBlockRoot)
                    .thenCompose(
                        preState -> {
                          if (preState.isEmpty()) {
                            // TODO-GLOAS: https://github.com/Consensys/teku/issues/9878 not sure
                            // about this fallback, but it's good enough for devnet-0 (handles edge
                            // cases for reference tests where parent node is the AnchorState)
                            return store.retrieveBlockState(slotAndBlockRoot);
                          }
                          return SafeFuture.completedFuture(preState);
                        });
              }
              return store.retrieveBlockState(slotAndBlockRoot);
            });
  }

  @Override
  public void applyExecutionPayloadToStore(
      final MutableStore store,
      final SignedExecutionPayloadEnvelope signedEnvelope,
      final BeaconState postState) {
    // Add new execution payload to store
    store.putExecutionPayloadAndState(signedEnvelope, postState);
  }

  @Override
  public Optional<Integer> getPayloadAttestationDueMillis() {
    final SpecConfigGloas configGloas = SpecConfigGloas.required(specConfig);
    return Optional.of(getSlotComponentDurationMillis(configGloas.getPayloadAttestationDueBps()));
  }

  /**
   * Computes dual block timeliness for Gloas: attestation deadline and PTC deadline.
   *
   * <p>Spec reference: record_block_timeliness (Gloas override)
   */
  @Override
  public boolean[] computeBlockTimeliness(
      final UInt64 blockSlot, final UInt64 currentSlot, final int millisIntoSlot) {
    final int attestationTimelinessLimit = getAttestationDueMillis();
    final int ptcTimelinessLimit = getPayloadAttestationDueMillis().orElseThrow();
    final boolean isTimelyAttestation =
        blockSlot.equals(currentSlot) && attestationTimelinessLimit > millisIntoSlot;
    final boolean isTimelyPtc =
        blockSlot.equals(currentSlot) && ptcTimelinessLimit > millisIntoSlot;
    return new boolean[] {isTimelyAttestation, isTimelyPtc};
  }

  // Checking of blob data availability is delayed until the processing of the execution payload
  @Override
  public AvailabilityChecker<?> createAvailabilityChecker(final SignedBeaconBlock block) {
    return AvailabilityChecker.NOOP_DATACOLUMN_SIDECAR;
  }

  // TODO-GLOAS: https://github.com/Consensys/teku/issues/10311 add a real data availability check
  // (not required for devnet-0)
  @Override
  public AvailabilityChecker<?> createAvailabilityChecker(
      final SignedExecutionPayloadEnvelope executionPayload) {
    return AvailabilityChecker.NOOP_DATACOLUMN_SIDECAR;
  }

  @Override
  public boolean shouldNotifyForkChoiceUpdatedOnBlock() {
    return false;
  }

  public boolean isBlockStatusFull(final ReadOnlyStore store, final BeaconBlock block) {
    return store.getExecutionPayloadIfAvailable(block.getRoot()).isPresent();
  }

  @Override
  public Optional<ForkChoiceUtilGloas> toVersionGloas() {
    return Optional.of(this);
  }

  /**
   * Determines whether proposer boost should be applied during weight computation.
   *
   * <p>In Gloas, proposer boost is conditionally suppressed to prevent equivocation-based reorgs.
   * If the head's parent was weak and from the previous slot, boost only applies if the head block
   * arrived before the PTC deadline (no timely equivocations detected).
   *
   * <p>Spec reference: should_apply_proposer_boost
   *
   * @param proposerBoostRoot the current proposer boost root, empty if none
   * @param headRoot the current head root
   * @param forkChoiceStrategy the fork choice strategy for looking up block data
   * @param reorgThreshold the threshold for the head weakness check
   * @param blockTimeliness the timeliness array for the head block, or null if unknown
   * @return true if proposer boost should be applied
   */
  // should_apply_proposer_boost
  public boolean shouldApplyProposerBoost(
      final Optional<Bytes32> proposerBoostRoot,
      final Bytes32 headRoot,
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final UInt64 reorgThreshold,
      final boolean[] blockTimeliness) {
    if (proposerBoostRoot.isEmpty()) {
      return false;
    }
    final Optional<Bytes32> maybeParentRoot = forkChoiceStrategy.blockParentRoot(headRoot);
    final Optional<UInt64> maybeHeadSlot = forkChoiceStrategy.blockSlot(headRoot);
    if (maybeParentRoot.isEmpty() || maybeHeadSlot.isEmpty()) {
      return true;
    }
    final Bytes32 parentRoot = maybeParentRoot.get();
    final UInt64 headSlot = maybeHeadSlot.get();
    final Optional<UInt64> maybeParentSlot = forkChoiceStrategy.blockSlot(parentRoot);
    if (maybeParentSlot.isEmpty()) {
      return true;
    }
    final UInt64 parentSlot = maybeParentSlot.get();
    // If parent is not from the previous slot, boost applies
    if (!parentSlot.increment().equals(headSlot)) {
      return true;
    }
    // If parent is not weak, boost applies
    if (!isHeadWeak(forkChoiceStrategy, parentRoot, reorgThreshold)) {
      return true;
    }
    // Parent is weak and from previous slot: only boost if PTC timeliness indicates no equivocation
    if (blockTimeliness == null || blockTimeliness.length <= PTC_TIMELINESS_INDEX) {
      return true;
    }
    return blockTimeliness[PTC_TIMELINESS_INDEX];
  }

  /**
   * Computes the attestation score for a block root.
   *
   * <p>Spec reference: get_attestation_score
   *
   * <p>TODO-GLOAS: The full implementation requires is_supporting_vote with payload-status-aware
   * vote checking (VoteTracker needs slot and payload_present fields). Currently uses protoarray
   * weight as an approximation, which already accounts for vote propagation and equivocating
   * validators.
   *
   * @param forkChoiceStrategy the fork choice strategy for accessing block weights
   * @param root the root of the block to score
   * @return the attestation weight supporting this block
   */
  // get_attestation_score
  UInt64 getAttestationScore(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy, final Bytes32 root) {
    return forkChoiceStrategy.getWeight(root).orElse(UInt64.ZERO);
  }

  /**
   * Determines if the head block is weak. In Gloas, this uses getAttestationScore instead of raw
   * protoarray weight.
   *
   * <p>Spec reference: is_head_weak (Gloas override)
   *
   * <p>TODO-GLOAS: Add weight from equivocating validators in the head slot's committees (prevents
   * equivocation-based reorgs). This requires access to the equivocating indices set and committee
   * assignments from the justified state.
   */
  @Override
  public boolean isHeadWeak(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 root,
      final UInt64 reorgThreshold) {
    final UInt64 attestationScore = getAttestationScore(forkChoiceStrategy, root);
    // TODO-GLOAS: Add weight of equivocating validators in head slot committees to
    // attestationScore.
    // In the spec, equivocating validators' weight is ADDED to the head score (making it harder
    // to reorg), as a safety measure to prevent equivocation-based reorgs.
    return attestationScore.isLessThan(reorgThreshold);
  }

  /**
   * Determines if the parent block is strong. In Gloas, this uses getAttestationScore instead of
   * raw protoarray weight.
   *
   * <p>Spec reference: is_parent_strong (Gloas override)
   *
   * <p>TODO-GLOAS: The spec also considers the parent's payload_status when computing the
   * attestation score via get_parent_payload_status. This requires is_supporting_vote
   * implementation.
   */
  @Override
  public boolean isParentStrong(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 parentRoot,
      final UInt64 parentThreshold) {
    final UInt64 attestationScore = getAttestationScore(forkChoiceStrategy, parentRoot);
    return attestationScore.isGreaterThan(parentThreshold);
  }

  /**
   * Determines the payload status of the parent block.
   *
   * <p>Spec reference:
   * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_parent_payload_status
   *
   * @param store the fork choice store
   * @param block the current block
   * @return PAYLOAD_STATUS_FULL if parent has full payload, PAYLOAD_STATUS_EMPTY otherwise
   */
  // get_parent_payload_status
  SafeFuture<PayloadStatus> getParentPayloadStatus(
      final ReadOnlyStore store, final BeaconBlock block) {
    return store
        .retrieveBlock(block.getParentRoot())
        .thenApply(
            parentBlock -> {
              if (parentBlock.isEmpty()) {
                throw new IllegalStateException("Parent block not found: " + block.getParentRoot());
              }
              final Optional<Bytes32> messageBlockHash =
                  parentBlock
                      .get()
                      .getBody()
                      .toVersionGloas()
                      .map(
                          bodyGloas ->
                              bodyGloas.getSignedExecutionPayloadBid().getMessage().getBlockHash());
              // if the parent block is pre-Gloas, we'd use the block state, there would be no
              // payload state
              if (messageBlockHash.isEmpty()) {
                return PAYLOAD_STATUS_EMPTY;
              }
              final Bytes32 parentBlockHash =
                  BeaconBlockBodyGloas.required(block.getBody())
                      .getSignedExecutionPayloadBid()
                      .getMessage()
                      .getParentBlockHash();
              return parentBlockHash.equals(messageBlockHash.get())
                  ? PayloadStatus.PAYLOAD_STATUS_FULL
                  : PAYLOAD_STATUS_EMPTY;
            });
  }

  /**
   * Checks if the parent node has a full payload.
   *
   * <p>Spec reference:
   * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_parent_node_full
   *
   * @param store the fork choice store
   * @param block the current block
   * @return true if parent has full payload status
   */
  // is_parent_node_full
  SafeFuture<Boolean> isParentNodeFull(final ReadOnlyStore store, final BeaconBlock block) {
    return getParentPayloadStatus(store, block)
        .thenApply(parentPayloadStatus -> parentPayloadStatus == PayloadStatus.PAYLOAD_STATUS_FULL);
  }
}
