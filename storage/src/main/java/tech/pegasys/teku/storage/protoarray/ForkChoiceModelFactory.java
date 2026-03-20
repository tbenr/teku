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
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;

/** Centralizes the storage-layer milestone split for forkchoice models. */
public class ForkChoiceModelFactory {

  private final Spec spec;
  private final ForkChoiceModel defaultModel = ForkChoiceModelDefault.INSTANCE;
  private final ForkChoiceModel gloasModel;

  public ForkChoiceModelFactory(final Spec spec) {
    this.spec = spec;
    final SpecVersion gloasSpec = spec.forMilestone(SpecMilestone.GLOAS);
    this.gloasModel =
        gloasSpec != null
            ? new ForkChoiceModelGloas(SpecConfigGloas.required(gloasSpec.getConfig()))
            : defaultModel;
  }

  ForkChoiceModel forSlot(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone().isGreaterThanOrEqualTo(SpecMilestone.GLOAS)
        ? gloasModel
        : defaultModel;
  }

  public void rebuildTrackedBlock(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final StoredBlockMetadata block,
      final Optional<SignedBeaconBlock> maybeBlock,
      final boolean optimisticallyProcessed) {
    forSlot(block.getBlockSlot())
        .rebuildTrackedBlock(
            protoArray, blockNodeIndex, block, maybeBlock, optimisticallyProcessed);
  }

  void onPrunedBlocks(final BlockNodeVariantsIndex blockNodeIndex) {
    defaultModel.onPrunedBlocks(blockNodeIndex);
    gloasModel.onPrunedBlocks(blockNodeIndex);
  }
}
