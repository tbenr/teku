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

package tech.pegasys.teku.validator.coordinator;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.kzg.KZGProof;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.Blob;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.execution.BlobAndCellProofs;
import tech.pegasys.teku.spec.datastructures.execution.BlobsBundle;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadResult;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerBlockProductionManager;
import tech.pegasys.teku.spec.logic.common.util.ExecutionPayloadProposalUtil.ExecutionPayloadProposalData;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.MiscHelpersGloas;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;

public class ExecutionPayloadFactoryGloas implements ExecutionPayloadFactory {

  private final Spec spec;
  private final ExecutionLayerBlockProductionManager executionLayerBlockProductionManager;

  public ExecutionPayloadFactoryGloas(
      final Spec spec,
      final ExecutionLayerBlockProductionManager executionLayerBlockProductionManager) {
    this.spec = spec;
    this.executionLayerBlockProductionManager = executionLayerBlockProductionManager;
  }

  @Override
  public SafeFuture<Optional<ExecutionPayloadEnvelopeContents>> createSelfBuiltExecutionPayload(
      final BeaconBlock block) {
    final Optional<ExecutionPayloadBid> maybeBid = block.getBody().getOptionalExecutionPayloadBid();
    // Only local flow for ePBS -> this result has been cached when creating the bid for the block
    final Optional<ExecutionPayloadResult> maybeCachedPayloadResult =
        executionLayerBlockProductionManager.getCachedPayloadResult(block.getSlot());
    if (maybeBid.isEmpty() || maybeCachedPayloadResult.isEmpty()) {
      return SafeFuture.completedFuture(Optional.empty());
    }
    final SchemaDefinitionsGloas schemaDefinitions =
        SchemaDefinitionsGloas.required(spec.atSlot(block.getSlot()).getSchemaDefinitions());
    return maybeCachedPayloadResult
        .get()
        .getPayloadResponseFutureFromLocalFlowRequired()
        .thenApply(
            getPayloadResponse -> {
              // the block may commit to a payload other than the one this node built for the slot
              if (!getPayloadResponse
                  .getExecutionPayload()
                  .hashTreeRoot()
                  .equals(maybeBid.get().getPayloadRoot())) {
                return Optional.empty();
              }
              final BlobsBundle blobsBundle = getPayloadResponse.getBlobsBundle().orElseThrow();
              final ExecutionPayloadEnvelope executionPayload =
                  spec.createExecutionPayloadEnvelope(
                      block,
                      new ExecutionPayloadProposalData(
                          getPayloadResponse.getExecutionPayload(),
                          getPayloadResponse.getExecutionRequests().orElseThrow(),
                          schemaDefinitions
                              .getBlobKzgCommitmentsSchema()
                              .createFromBlobsBundle(blobsBundle)));
              return Optional.of(
                  schemaDefinitions
                      .getExecutionPayloadEnvelopeContentsSchema()
                      .create(executionPayload, blobsBundle.getProofs(), blobsBundle.getBlobs()));
            });
  }

  @Override
  public List<DataColumnSidecar> createDataColumnSidecars(
      final ExecutionPayloadEnvelopeContents executionPayloadEnvelopeContents) {
    return createDataColumnSidecars(
        executionPayloadEnvelopeContents.getExecutionPayloadEnvelope(),
        executionPayloadEnvelopeContents.getBlobs().stream().toList(),
        executionPayloadEnvelopeContents.getKzgProofs().stream()
            .map(SszKZGProof::getKZGProof)
            .toList());
  }

  private List<DataColumnSidecar> createDataColumnSidecars(
      final ExecutionPayloadEnvelope executionPayload,
      final List<Blob> blobs,
      final List<KZGProof> proofs) {
    final SpecVersion specVersion = spec.atSlot(executionPayload.getSlot());
    final int numberOfColumns =
        SpecConfigFulu.required(specVersion.getConfig()).getNumberOfColumns();
    final List<BlobAndCellProofs> blobAndCellProofsList =
        IntStream.range(0, blobs.size())
            .mapToObj(
                index ->
                    new BlobAndCellProofs(
                        blobs.get(index),
                        proofs.stream()
                            .skip((long) index * numberOfColumns)
                            .limit(numberOfColumns)
                            .toList()))
            .toList();
    return MiscHelpersGloas.required(specVersion.miscHelpers())
        .constructDataColumnSidecars(executionPayload, blobAndCellProofsList);
  }
}
