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
 * Gloas payload status tiebreaker for fork-choice head selection.
 *
 * <p>Spec: get_payload_status_tiebreaker
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_payload_status_tiebreaker
 */
class GloasPayloadStatusTiebreaker implements PayloadStatusTiebreaker {
  private final UInt64 currentSlot;
  private final Optional<Bytes32> proposerBoostRoot;
  private final int payloadTimelyThreshold;
  private final ToIntFunction<Bytes32> ptcPresentVoteCountLookup;

  GloasPayloadStatusTiebreaker(
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot,
      final int payloadTimelyThreshold,
      final ToIntFunction<Bytes32> ptcPresentVoteCountLookup) {
    this.currentSlot = currentSlot;
    this.proposerBoostRoot = proposerBoostRoot;
    this.payloadTimelyThreshold = payloadTimelyThreshold;
    this.ptcPresentVoteCountLookup = ptcPresentVoteCountLookup;
  }

  @Override
  public int compare(
      final ProtoNode child,
      final ProtoNode bestChild,
      final ProtoNode parent,
      final ProtoArray protoArray) {
    return Integer.compare(
        computePayloadStatusTiebreaker(child, protoArray),
        computePayloadStatusTiebreaker(bestChild, protoArray));
  }

  /**
   * Spec: get_payload_status_tiebreaker
   * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_payload_status_tiebreaker
   */
  private int computePayloadStatusTiebreaker(final ProtoNode node, final ProtoArray protoArray) {
    if (node.getPayloadStatus() == PAYLOAD_STATUS_PENDING
        || !node.getBlockSlot().plus(1).equals(currentSlot)) {
      return node.getPayloadStatus().getValue();
    }
    if (node.getPayloadStatus() == PAYLOAD_STATUS_EMPTY) {
      return 1;
    }
    // FULL
    return shouldExtendPayload(node.getBlockRoot(), protoArray) ? 2 : 0;
  }

  /**
   * Spec: should_extend_payload
   * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-should_extend_payload
   */
  private boolean shouldExtendPayload(final Bytes32 blockRoot, final ProtoArray protoArray) {
    // Spec: is_payload_timely(store, root)
    if (isPayloadTimely(blockRoot, protoArray)) {
      return true;
    }
    // Spec: proposer_root == Root()
    if (proposerBoostRoot.isEmpty()) {
      return true;
    }
    // Spec: store.blocks[proposer_root].parent_root != root
    final Optional<ProtoNode> proposerNode = protoArray.getProtoNode(proposerBoostRoot.get());
    if (proposerNode.isEmpty()) {
      return true;
    }
    if (!proposerNode.get().getParentRoot().equals(blockRoot)) {
      return true;
    }
    // Spec: is_parent_node_full(store, store.blocks[proposer_root])
    return protoArray.hasFullNode(blockRoot);
  }

  /**
   * Spec: is_payload_timely Returns true if the block has a FULL node AND PTC "present" votes
   * exceed threshold.
   */
  private boolean isPayloadTimely(final Bytes32 blockRoot, final ProtoArray protoArray) {
    if (!protoArray.hasFullNode(blockRoot)) {
      return false;
    }
    return ptcPresentVoteCountLookup.applyAsInt(blockRoot) > payloadTimelyThreshold;
  }
}
