/*
 * Copyright Consensys Software Inc., 2023
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

package tech.pegasys.teku.validator.coordinator;

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.ethereum.performance.trackers.BlockProductionPerformance;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.execution.BlobsBundle;
import tech.pegasys.teku.spec.datastructures.metadata.BlockContainerAndMetaData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsDeneb;

public class BlockFactoryDeneb extends BlockFactoryPhase0 {

  private final SchemaDefinitionsDeneb schemaDefinitionsDeneb;

  public BlockFactoryDeneb(final Spec spec, final BlockOperationSelectorFactory operationSelector) {
    super(spec, operationSelector);
    this.schemaDefinitionsDeneb =
        SchemaDefinitionsDeneb.required(
            spec.forMilestone(SpecMilestone.DENEB).getSchemaDefinitions());
  }

  @Override
  public SafeFuture<BlockContainerAndMetaData> createUnsignedBlock(
      final BeaconState blockSlotState,
      final UInt64 proposalSlot,
      final BLSSignature randaoReveal,
      final Optional<Bytes32> optionalGraffiti,
      final Optional<Boolean> requestedBlinded,
      final Optional<UInt64> requestedBuilderBoostFactor,
      final BlockProductionPerformance blockProductionPerformance) {
    return super.createUnsignedBlock(
            blockSlotState,
            proposalSlot,
            randaoReveal,
            optionalGraffiti,
            requestedBlinded,
            requestedBuilderBoostFactor,
            blockProductionPerformance)
        .thenCompose(
            blockContainerAndMetaData -> {
              if (blockContainerAndMetaData.blockContainer().isBlinded()) {
                return SafeFuture.completedFuture(blockContainerAndMetaData);
              }
              // The execution BlobsBundle has been cached as part of the block creation
              return operationSelector
                  .createBlobsBundleSelector()
                  .apply(blockContainerAndMetaData.blockContainer().getBlock())
                  .thenApply(
                      blobsBundle -> createBlockContents(blockContainerAndMetaData, blobsBundle));
            });
  }

  @Override
  public List<BlobSidecar> createBlobSidecars(final SignedBlockContainer blockContainer) {
    return operationSelector.createBlobSidecarsSelector().apply(blockContainer);
  }

  private BlockContainerAndMetaData createBlockContents(
      final BlockContainerAndMetaData blockContainerAndMetaData, final BlobsBundle blobsBundle) {
    return blockContainerAndMetaData.withBlockContainer(
        schemaDefinitionsDeneb
            .getBlockContentsSchema()
            .create(
                blockContainerAndMetaData.blockContainer().getBlock(),
                blobsBundle.getProofs(),
                blobsBundle.getBlobs()));
  }
}
