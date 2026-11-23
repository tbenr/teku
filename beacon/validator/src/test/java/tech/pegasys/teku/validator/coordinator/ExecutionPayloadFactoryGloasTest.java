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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.safeJoin;
import static tech.pegasys.teku.spec.config.SpecConfigGloas.BUILDER_INDEX_SELF_BUILD;

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.execution.BlobsBundle;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadResult;
import tech.pegasys.teku.spec.datastructures.execution.GetPayloadResponse;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerBlockProductionManager;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionPayloadFactoryGloasTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();

  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  private final ExecutionLayerBlockProductionManager executionLayerBlockProductionManager =
      mock(ExecutionLayerBlockProductionManager.class);

  private BlobsBundle blobsBundle;
  private GetPayloadResponse getPayloadResponse;

  private final ExecutionPayloadFactoryGloas executionPayloadFactory =
      new ExecutionPayloadFactoryGloas(spec, executionLayerBlockProductionManager);

  @Test
  public void createsSelfBuiltExecutionPayload() {
    final UInt64 slot = UInt64.ONE;
    prepareGetPayloadResponse(slot);
    final BeaconBlock block =
        blockCommittingTo(slot, getPayloadResponse.getExecutionPayload().hashTreeRoot());
    setupCachingOfThePayloadResult(slot, getPayloadResponse);

    final Optional<ExecutionPayloadEnvelopeContents> maybeContents =
        safeJoin(executionPayloadFactory.createSelfBuiltExecutionPayload(block));

    assertThat(maybeContents).isPresent();
    // the envelope comes with the blob data cached alongside the payload
    assertThat(maybeContents.get().getBlobs().asList()).isEqualTo(blobsBundle.getBlobs());
    assertThat(maybeContents.get().getKzgProofs().stream().map(SszKZGProof::getKZGProof).toList())
        .isEqualTo(blobsBundle.getProofs());
    final ExecutionPayloadEnvelope executionPayload =
        maybeContents.get().getExecutionPayloadEnvelope();
    assertThat(executionPayload.getPayload()).isEqualTo(getPayloadResponse.getExecutionPayload());
    assertThat(executionPayload.getExecutionRequests())
        .isEqualTo(getPayloadResponse.getExecutionRequests().orElseThrow());
    assertThat(executionPayload.getBeaconBlockRoot()).isEqualTo(block.getRoot());
    assertThat(executionPayload.getParentBeaconBlockRoot()).isEqualTo(block.getParentRoot());
    assertThat(executionPayload.getSlot()).isEqualTo(slot);
  }

  @Test
  public void doesNotCreateSelfBuiltExecutionPayloadWhenPayloadResultIsNotCached() {
    final UInt64 slot = UInt64.ONE;
    final BeaconBlock block = blockCommittingTo(slot, dataStructureUtil.randomBytes32());
    when(executionLayerBlockProductionManager.getCachedPayloadResult(slot))
        .thenReturn(Optional.empty());

    assertThat(safeJoin(executionPayloadFactory.createSelfBuiltExecutionPayload(block))).isEmpty();
  }

  @Test
  public void doesNotCreateSelfBuiltExecutionPayloadWhenBlockCommitsToAnotherPayload() {
    final UInt64 slot = UInt64.ONE;
    prepareGetPayloadResponse(slot);
    final BeaconBlock block = blockCommittingTo(slot, dataStructureUtil.randomBytes32());
    setupCachingOfThePayloadResult(slot, getPayloadResponse);

    assertThat(safeJoin(executionPayloadFactory.createSelfBuiltExecutionPayload(block))).isEmpty();
  }

  @Test
  public void createsDataColumnSidecars() {
    final UInt64 slot = UInt64.ONE;
    final SpecConfigFulu specConfig = SpecConfigFulu.required(spec.atSlot(slot).getConfig());
    blobsBundle = dataStructureUtil.randomBlobsBundle(3);
    final ExecutionPayloadEnvelope executionPayload =
        dataStructureUtil.randomExecutionPayloadEnvelope(slot);
    final ExecutionPayloadEnvelopeContents contents =
        SchemaDefinitionsGloas.required(spec.atSlot(slot).getSchemaDefinitions())
            .getExecutionPayloadEnvelopeContentsSchema()
            .create(executionPayload, blobsBundle.getProofs(), blobsBundle.getBlobs());

    final List<DataColumnSidecar> dataColumnSidecars =
        executionPayloadFactory.createDataColumnSidecars(contents);

    assertThat(dataColumnSidecars.size()).isEqualTo(specConfig.getNumberOfColumns());
    dataColumnSidecars.forEach(
        dataColumnSidecar ->
            assertThat(dataColumnSidecar.getBeaconBlockRoot())
                .isEqualTo(executionPayload.getBeaconBlockRoot()));
  }

  private void prepareGetPayloadResponse(final UInt64 slot) {
    blobsBundle = dataStructureUtil.randomBlobsBundle(3);
    getPayloadResponse =
        new GetPayloadResponse(
            dataStructureUtil.randomExecutionPayload(slot),
            dataStructureUtil.randomUInt256(),
            blobsBundle,
            false,
            dataStructureUtil.randomExecutionRequests(slot));
  }

  private BeaconBlock blockCommittingTo(final UInt64 slot, final Bytes32 payloadRoot) {
    final ExecutionPayloadBid bid =
        dataStructureUtil.randomExecutionPayloadBid(
            slot,
            BUILDER_INDEX_SELF_BUILD,
            dataStructureUtil.randomBytes32(),
            payloadRoot,
            dataStructureUtil.randomBytes32());
    return dataStructureUtil.randomBeaconBlock(
        slot,
        dataStructureUtil.randomBeaconBlockBody(
            slot,
            builder ->
                builder.signedExecutionPayloadBid(
                    dataStructureUtil.randomSignedExecutionPayloadBid(bid))));
  }

  private void setupCachingOfThePayloadResult(
      final UInt64 slot, final GetPayloadResponse getPayloadResponse) {
    // simulate caching of the payload result
    when(executionLayerBlockProductionManager.getCachedPayloadResult(slot))
        .thenReturn(
            Optional.of(
                ExecutionPayloadResult.createForLocalFlow(
                    dataStructureUtil.randomPayloadExecutionContext(slot, false),
                    SafeFuture.completedFuture(getPayloadResponse))));
  }
}
