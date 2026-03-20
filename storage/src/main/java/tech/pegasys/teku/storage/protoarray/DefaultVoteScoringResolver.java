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
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.forkchoice.VoteTracker;

/** Pre-Gloas vote routing: every vote resolves directly to the block's base node. */
class DefaultVoteScoringResolver implements VoteScoringResolver {

  static final DefaultVoteScoringResolver INSTANCE = new DefaultVoteScoringResolver();

  private DefaultVoteScoringResolver() {}

  @Override
  public Optional<ForkChoiceNode> resolveCurrentNode(
      final VoteTracker vote, final ProtoArray protoArray, final BlockNodeVariantsIndex blockNodeIndex) {
    return blockNodeIndex.getBaseNode(vote.getCurrentRoot());
  }

  @Override
  public Optional<ForkChoiceNode> resolveNextNode(
      final VoteTracker vote, final ProtoArray protoArray, final BlockNodeVariantsIndex blockNodeIndex) {
    return blockNodeIndex.getBaseNode(vote.getNextRoot());
  }
}
