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

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.forkchoice.VoteTracker;

/**
 * Storage-side vote routing for the Gloas fork-choice tree.
 *
 * <p>This is not a literal spec helper. It is the protoarray projection of the Python logic spread
 * across `LatestMessage`, `update_latest_messages(...)`, `is_supporting_vote(...)`, and
 * `get_attestation_score(...)`:
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-latestmessage
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-update_latest_messages
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_supporting_vote
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-get_attestation_score
 *
 * <p>Votes at or before the block's own slot stay on the canonical block node. Later votes are
 * routed to the EMPTY or FULL child according to `payload_present`.
 */
class GloasVoteScoringResolver implements VoteScoringResolver {

  static final GloasVoteScoringResolver INSTANCE = new GloasVoteScoringResolver();

  private GloasVoteScoringResolver() {}

  @Override
  public Optional<Integer> resolveCurrentIndex(
      final VoteTracker vote, final ProtoArray protoArray) {
    return resolveIndex(
        vote.getCurrentRoot(), vote.getCurrentSlot(), vote.isCurrentPayloadPresent(), protoArray);
  }

  @Override
  public Optional<Integer> resolveNextIndex(final VoteTracker vote, final ProtoArray protoArray) {
    return resolveIndex(
        vote.getNextRoot(), vote.getNextSlot(), vote.isNextPayloadPresent(), protoArray);
  }

  private Optional<Integer> resolveIndex(
      final Bytes32 voteRoot,
      final UInt64 voteSlot,
      final boolean payloadPresent,
      final ProtoArray protoArray) {
    final Optional<UInt64> blockSlot =
        protoArray.getProtoNode(voteRoot).map(ProtoNode::getBlockSlot);
    if (blockSlot.isPresent() && voteSlot.isLessThanOrEqualTo(blockSlot.get())) {
      return protoArray.getIndexByRoot(voteRoot);
    }
    if (payloadPresent) {
      return protoArray.getFullNodeIndex(voteRoot).or(() -> protoArray.getIndexByRoot(voteRoot));
    }
    return protoArray.getEmptyNodeIndex(voteRoot).or(() -> protoArray.getIndexByRoot(voteRoot));
  }
}
