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

package tech.pegasys.teku.storage.protoarray;

import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;

import java.util.Optional;
import java.util.function.ToIntFunction;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Gloas head-selection comparison policy for EMPTY/FULL sibling nodes.
 *
 * <p>This class groups the Python helpers used by modified `get_head(...)`:
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-get_head
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-get_weight
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_payload_status_tiebreaker
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-should_extend_payload
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_payload_timely
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_payload_data_available
 */
class GloasHeadSelectionPolicy implements HeadSelectionPolicy {
  private final UInt64 currentSlot;
  private final Optional<Bytes32> proposerBoostRoot;
  private final int payloadTimelyThreshold;
  private final int dataAvailabilityTimelyThreshold;
  private final ToIntFunction<Bytes32> ptcPresentVoteCountLookup;
  private final ToIntFunction<Bytes32> ptcDataAvailableVoteCountLookup;

  GloasHeadSelectionPolicy(
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot,
      final int payloadTimelyThreshold,
      final int dataAvailabilityTimelyThreshold,
      final ToIntFunction<Bytes32> ptcPresentVoteCountLookup,
      final ToIntFunction<Bytes32> ptcDataAvailableVoteCountLookup) {
    this.currentSlot = currentSlot;
    this.proposerBoostRoot = proposerBoostRoot;
    this.payloadTimelyThreshold = payloadTimelyThreshold;
    this.dataAvailabilityTimelyThreshold = dataAvailabilityTimelyThreshold;
    this.ptcPresentVoteCountLookup = ptcPresentVoteCountLookup;
    this.ptcDataAvailableVoteCountLookup = ptcDataAvailableVoteCountLookup;
  }

  @Override
  public int compareChildren(
      final ProtoNode candidateChild,
      final ProtoNode currentBestChild,
      final ProtoNode parent,
      final ProtoArray protoArray) {
    // Spec mapping: the extra Gloas sort key in modified get_head only applies to the EMPTY/FULL
    // children returned by get_node_children(parent_root).
    if (!candidateChild.getBlockRoot().equals(parent.getBlockRoot())
        || !currentBestChild.getBlockRoot().equals(parent.getBlockRoot())) {
      return 0;
    }

    final UInt64 candidateEffectiveWeight = effectiveWeight(candidateChild);
    final UInt64 currentEffectiveWeight = effectiveWeight(currentBestChild);
    final int weightComparison = candidateEffectiveWeight.compareTo(currentEffectiveWeight);
    if (weightComparison != 0) {
      return weightComparison;
    }

    return Integer.compare(
        computePayloadStatusTiebreaker(candidateChild, protoArray),
        computePayloadStatusTiebreaker(currentBestChild, protoArray));
  }

  private UInt64 effectiveWeight(final ProtoNode node) {
    // Spec mapping: modified get_weight(store, node)
    if (node.getPayloadStatus() == PAYLOAD_STATUS_PENDING
        || !node.getBlockSlot().plus(1).equals(currentSlot)) {
      return node.getWeight();
    }
    return UInt64.ZERO;
  }

  private int computePayloadStatusTiebreaker(final ProtoNode node, final ProtoArray protoArray) {
    // Spec mapping: get_payload_status_tiebreaker(store, node)
    if (node.getPayloadStatus() == PAYLOAD_STATUS_PENDING
        || !node.getBlockSlot().plus(1).equals(currentSlot)) {
      return node.getPayloadStatus().getValue();
    }
    if (node.getPayloadStatus() == PAYLOAD_STATUS_EMPTY) {
      return 1;
    }
    return shouldExtendPayload(node.getBlockRoot(), protoArray) ? 2 : 0;
  }

  private boolean shouldExtendPayload(final Bytes32 blockRoot, final ProtoArray protoArray) {
    // Spec mapping: should_extend_payload(store, root)
    // The proposer-equivocation decision is delegated to the surrounding store/fork-choice code.
    if (isPayloadTimely(blockRoot, protoArray) && isPayloadDataAvailable(blockRoot, protoArray)) {
      return true;
    }
    if (proposerBoostRoot.isEmpty()) {
      return true;
    }
    final Optional<ProtoNode> proposerNode = protoArray.getProtoNode(proposerBoostRoot.get());
    if (proposerNode.isEmpty()) {
      return true;
    }
    if (!proposerNode.get().getParentRoot().equals(blockRoot)) {
      return true;
    }
    return protoArray.isParentNodeFull(blockRoot, proposerNode.get());
  }

  private boolean isPayloadTimely(final Bytes32 blockRoot, final ProtoArray protoArray) {
    // Spec mapping: is_payload_timely(store, root)
    if (!protoArray.hasFullNode(blockRoot)) {
      return false;
    }
    return ptcPresentVoteCountLookup.applyAsInt(blockRoot) > payloadTimelyThreshold;
  }

  private boolean isPayloadDataAvailable(final Bytes32 blockRoot, final ProtoArray protoArray) {
    // Spec mapping: is_payload_data_available(store, root)
    if (!protoArray.hasFullNode(blockRoot)) {
      return false;
    }
    return ptcDataAvailableVoteCountLookup.applyAsInt(blockRoot) > dataAvailabilityTimelyThreshold;
  }
}
