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
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.StateAndBlockSummary;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.gloas.BeaconBlockBodyGloas;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;
import tech.pegasys.teku.spec.datastructures.forkchoice.MutableStore;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyForkChoiceStrategy;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyStore;
import tech.pegasys.teku.spec.datastructures.forkchoice.VoteAccessor;
import tech.pegasys.teku.spec.datastructures.forkchoice.VoteTracker;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.statetransition.availability.AvailabilityChecker;
import tech.pegasys.teku.spec.logic.common.util.ForkChoiceUtil;
import tech.pegasys.teku.spec.logic.versions.fulu.util.ForkChoiceUtilFulu;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.BeaconStateAccessorsGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.MiscHelpersGloas;
import tech.pegasys.teku.spec.logic.versions.gloas.statetransition.epoch.EpochProcessorGloas;

public class ForkChoiceUtilGloas extends ForkChoiceUtilFulu {

  public static final int PTC_TIMELINESS_INDEX = 1;

  public ForkChoiceUtilGloas(
      final SpecConfigGloas specConfig,
      final BeaconStateAccessorsGloas beaconStateAccessors,
      final EpochProcessorGloas epochProcessor,
      final AttestationUtilGloas attestationUtil,
      final MiscHelpersGloas miscHelpers) {
    super(specConfig, beaconStateAccessors, epochProcessor, attestationUtil, miscHelpers);
  }

  @Override
  public boolean shouldUpdateVote(
      final VoteTracker vote, final UInt64 targetEpoch, final UInt64 slot) {
    return slot.isGreaterThan(vote.getNextSlot()) || vote.equals(VoteTracker.DEFAULT);
  }

  public static ForkChoiceUtilGloas required(final ForkChoiceUtil forkChoiceUtil) {
    checkArgument(
        forkChoiceUtil instanceof ForkChoiceUtilGloas,
        "Expected a ForkChoiceUtilGloas but was %s",
        forkChoiceUtil.getClass());
    return (ForkChoiceUtilGloas) forkChoiceUtil;
  }

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

  @Override
  public SafeFuture<StateAndBlockSummary> retrieveNewChainHeadStateAndBlockSummary(
      final Bytes32 root, final UInt64 chainHeadSlot, final ReadOnlyStore store) {
    return store
        .retrieveStateAndBlockSummary(root)
        .thenApply(
            maybeHead ->
                maybeHead.orElseThrow(
                    () ->
                        new IllegalStateException(
                            String.format(
                                "Unable to update head block as of slot %s.  Block is unavailable: %s.",
                                chainHeadSlot, root))))
        // TODO-GLOAS: https://github.com/Consensys/teku/issues/9878 this is just a workaround
        // for devnet-0, we may require a more proper implementation, when the complete fork
        // choice is implemented
        .thenApply(
            stateAndBlockSummary ->
                store
                    .getExecutionPayloadStateIfAvailable(root)
                    .map(
                        executionPayloadState ->
                            StateAndBlockSummary.create(
                                stateAndBlockSummary.getBlockSummary(), executionPayloadState))
                    .orElse(stateAndBlockSummary));
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
   * If the boosted block's parent was weak and from the previous slot, boost only applies if there
   * are no timely equivocations from the same proposer.
   *
   * <p>Spec reference: should_apply_proposer_boost
   *
   * @param proposerBoostRoot the current proposer boost root, empty if none
   * @param forkChoiceStrategy the fork choice strategy for looking up block data
   * @param reorgThreshold the threshold for the head weakness check
   * @return true if proposer boost should be applied
   */
  // should_apply_proposer_boost
  public boolean shouldApplyProposerBoost(
      final Optional<Bytes32> proposerBoostRoot,
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final UInt64 reorgThreshold) {
    if (proposerBoostRoot.isEmpty()) {
      return false;
    }
    final Bytes32 boostRoot = proposerBoostRoot.get();
    final Optional<Bytes32> maybeParentRoot = forkChoiceStrategy.blockParentRoot(boostRoot);
    final Optional<UInt64> maybeBlockSlot = forkChoiceStrategy.blockSlot(boostRoot);
    if (maybeParentRoot.isEmpty() || maybeBlockSlot.isEmpty()) {
      return true;
    }
    final Bytes32 parentRoot = maybeParentRoot.get();
    final UInt64 blockSlot = maybeBlockSlot.get();
    final Optional<UInt64> maybeParentSlot = forkChoiceStrategy.blockSlot(parentRoot);
    if (maybeParentSlot.isEmpty()) {
      return true;
    }
    // Apply proposer boost if parent is not from the previous slot
    if (maybeParentSlot.get().increment().isLessThan(blockSlot)) {
      return true;
    }
    // Apply proposer boost if parent is not weak
    if (!isHeadWeak(forkChoiceStrategy, parentRoot, reorgThreshold)) {
      return true;
    }
    // Parent is weak and from the previous slot.
    // TODO-GLOAS: Check for equivocating blocks from the same proposer as the parent.
    // The spec checks if there are any OTHER blocks with the same proposer_index, in the slot
    // before the boosted block, that are PTC-timely (indicating a proposer equivocation).
    // Currently equivocating blocks are dropped at gossip validation
    // (EQUIVOCATING_BLOCK_FOR_SLOT_PROPOSER), so this condition always passes.
    // When is_proposer_equivocation is implemented, the equivocation check should be added here.
    return true;
  }

  /**
   * Checks whether a vote (LatestMessage) supports a ForkChoiceNode.
   *
   * <p>Spec reference: is_supporting_vote
   *
   * @param nodeRoot the root of the fork choice node
   * @param nodePayloadStatus the payload status of the fork choice node
   * @param voteRoot the root the validator voted for
   * @param voteSlot the slot of the vote
   * @param votePayloadPresent whether the vote signals payload presence
   * @param forkChoiceStrategy for ancestor lookups
   * @return true if the vote supports the node
   */
  boolean isSupportingVote(
      final Bytes32 nodeRoot,
      final ForkChoicePayloadStatus nodePayloadStatus,
      final Bytes32 voteRoot,
      final UInt64 voteSlot,
      final boolean votePayloadPresent,
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy) {
    final Optional<UInt64> maybeBlockSlot = forkChoiceStrategy.blockSlot(nodeRoot);
    if (maybeBlockSlot.isEmpty()) {
      return false;
    }
    final UInt64 blockSlot = maybeBlockSlot.get();

    if (nodeRoot.equals(voteRoot)) {
      // Direct vote for this root
      if (nodePayloadStatus == PAYLOAD_STATUS_PENDING) {
        return true;
      }
      if (voteSlot.isLessThanOrEqualTo(blockSlot)) {
        return false;
      }
      if (votePayloadPresent) {
        return nodePayloadStatus == PAYLOAD_STATUS_FULL;
      } else {
        return nodePayloadStatus == PAYLOAD_STATUS_EMPTY;
      }
    } else {
      // Ancestor vote: check if the node is an ancestor of the vote
      final Optional<Bytes32> ancestorRoot = forkChoiceStrategy.getAncestor(voteRoot, blockSlot);
      if (ancestorRoot.isEmpty() || !nodeRoot.equals(ancestorRoot.get())) {
        return false;
      }
      // For PENDING, any payload status matches (the node hasn't been resolved yet)
      if (nodePayloadStatus == PAYLOAD_STATUS_PENDING) {
        return true;
      }
      // Walk the vote's parent chain to find the payload status at the ancestor's slot.
      // With FULL nodes in protoarray, a FULL-descendant path walks through the FULL node,
      // while an EMPTY-descendant path walks through the block node (PENDING).
      final ForkChoicePayloadStatus ancestorStatus =
          forkChoiceStrategy
              .getAncestorPayloadStatus(voteRoot, blockSlot)
              .orElse(PAYLOAD_STATUS_PENDING);
      return nodePayloadStatus == ancestorStatus;
    }
  }

  /**
   * Computes the attestation score for a fork choice node by iterating validator votes.
   *
   * <p>Spec reference: get_attestation_score
   *
   * @param nodeRoot the root of the node to compute the score for
   * @param nodePayloadStatus the payload status of the node
   * @param forkChoiceStrategy for ancestor lookups and block data
   * @param voteAccessor read-only access to validator votes
   * @param justifiedState for active/unslashed validators and effective balances
   * @return the total attestation weight supporting this node
   */
  UInt64 getAttestationScore(
      final Bytes32 nodeRoot,
      final ForkChoicePayloadStatus nodePayloadStatus,
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final VoteAccessor voteAccessor,
      final BeaconState justifiedState) {
    final UInt64 currentEpoch = beaconStateAccessors.getCurrentEpoch(justifiedState);
    final IntList activeIndices =
        beaconStateAccessors.getActiveValidatorIndices(justifiedState, currentEpoch);

    long totalWeight = 0;
    for (final int validatorIndex : activeIndices) {
      if (justifiedState.getValidators().get(validatorIndex).isSlashed()) {
        continue;
      }
      final VoteTracker vote = voteAccessor.getVote(UInt64.valueOf(validatorIndex));
      if (vote.equals(VoteTracker.DEFAULT)) {
        continue;
      }
      if (vote.isEquivocating()) {
        continue;
      }
      if (isSupportingVote(
          nodeRoot,
          nodePayloadStatus,
          vote.getNextRoot(),
          vote.getNextSlot(),
          vote.isNextPayloadPresent(),
          forkChoiceStrategy)) {
        totalWeight +=
            justifiedState.getValidators().get(validatorIndex).getEffectiveBalance().longValue();
      }
    }
    return UInt64.valueOf(totalWeight);
  }

  /**
   * Computes the weight of equivocating validators in the head block's committees.
   *
   * <p>In Gloas, equivocating validators' effective balance is ADDED to the head weight, making it
   * harder to reorg. This ensures is_head_weak is monotonic: more attestations can only change the
   * output from true to false.
   *
   * @param headSlot the slot of the head block
   * @param forkChoiceStrategy for block data access
   * @param voteAccessor read-only access to validator votes
   * @param headState the head block's state (for committee computation)
   * @param justifiedState for effective balances
   * @return the total equivocating weight in head slot committees
   */
  UInt64 computeEquivocatingCommitteeWeight(
      final UInt64 headSlot,
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final VoteAccessor voteAccessor,
      final BeaconState headState,
      final BeaconState justifiedState) {
    final UInt64 epoch = miscHelpers.computeEpochAtSlot(headSlot);
    final UInt64 committeesPerSlot =
        beaconStateAccessors.getCommitteeCountPerSlot(headState, epoch);

    long equivocatingWeight = 0;
    for (UInt64 index = UInt64.ZERO;
        index.isLessThan(committeesPerSlot);
        index = index.increment()) {
      final IntList committee = beaconStateAccessors.getBeaconCommittee(headState, headSlot, index);
      for (final int validatorIndex : committee) {
        final VoteTracker vote = voteAccessor.getVote(UInt64.valueOf(validatorIndex));
        if (vote.isEquivocating()) {
          equivocatingWeight +=
              justifiedState.getValidators().get(validatorIndex).getEffectiveBalance().longValue();
        }
      }
    }
    return UInt64.valueOf(equivocatingWeight);
  }

  /**
   * Extended isHeadWeak for Gloas with full attestation score and equivocating committee weight.
   *
   * <p>Spec reference: is_head_weak (Gloas override)
   *
   * @param forkChoiceStrategy the fork choice strategy
   * @param root the head block root
   * @param reorgThreshold the threshold for weak head detection
   * @param voteAccessor read-only access to validator votes
   * @param headState the head block's state (for committee computation)
   * @param justifiedState for effective balances and attestation score
   * @return true if the head is weak
   */
  public boolean isHeadWeak(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 root,
      final UInt64 reorgThreshold,
      final VoteAccessor voteAccessor,
      final BeaconState headState,
      final BeaconState justifiedState) {
    // Compute attestation score with PAYLOAD_STATUS_PENDING (all votes count)
    UInt64 headWeight =
        getAttestationScore(
            root, PAYLOAD_STATUS_PENDING, forkChoiceStrategy, voteAccessor, justifiedState);

    // Add weight from equivocating validators in head slot committees
    final Optional<UInt64> maybeHeadSlot = forkChoiceStrategy.blockSlot(root);
    if (maybeHeadSlot.isPresent()) {
      final UInt64 equivocatingWeight =
          computeEquivocatingCommitteeWeight(
              maybeHeadSlot.get(), forkChoiceStrategy, voteAccessor, headState, justifiedState);
      headWeight = headWeight.plus(equivocatingWeight);
    }

    return headWeight.isLessThan(reorgThreshold);
  }

  /**
   * Fallback isHeadWeak without extended data. Uses protoarray weight as approximation.
   *
   * <p>Called from shouldApplyProposerBoost where vote data may not be available.
   */
  @Override
  public boolean isHeadWeak(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 root,
      final UInt64 reorgThreshold) {
    // Fallback: use protoarray weight (correct for PENDING, but missing equivocating weight)
    final UInt64 attestationScore = forkChoiceStrategy.getWeight(root).orElse(UInt64.ZERO);
    return attestationScore.isLessThan(reorgThreshold);
  }

  /**
   * Extended isParentStrong for Gloas with full attestation score using payload status.
   *
   * <p>Spec reference: is_parent_strong (Gloas override)
   */
  public boolean isParentStrong(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 parentRoot,
      final UInt64 parentThreshold,
      final ForkChoicePayloadStatus parentPayloadStatus,
      final VoteAccessor voteAccessor,
      final BeaconState justifiedState) {
    final UInt64 attestationScore =
        getAttestationScore(
            parentRoot, parentPayloadStatus, forkChoiceStrategy, voteAccessor, justifiedState);
    return attestationScore.isGreaterThan(parentThreshold);
  }

  /**
   * Fallback isParentStrong without extended data. Uses protoarray weight as approximation.
   *
   * <p>Called when vote data or payload status is not available.
   */
  @Override
  public boolean isParentStrong(
      final ReadOnlyForkChoiceStrategy forkChoiceStrategy,
      final Bytes32 parentRoot,
      final UInt64 parentThreshold) {
    final UInt64 attestationScore = forkChoiceStrategy.getWeight(parentRoot).orElse(UInt64.ZERO);
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
  public SafeFuture<ForkChoicePayloadStatus> getParentPayloadStatus(
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
                  ? PAYLOAD_STATUS_FULL
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
        .thenApply(parentPayloadStatus -> parentPayloadStatus == PAYLOAD_STATUS_FULL);
  }
}
