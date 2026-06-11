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

package tech.pegasys.teku.validator.coordinator.publisher;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.DataColumnSidecarGossipChannel;
import tech.pegasys.teku.networking.eth2.gossip.ExecutionPayloadGossipChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadEnvelope;
import tech.pegasys.teku.statetransition.blobs.RemoteOrigin;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadManager;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;
import tech.pegasys.teku.validator.coordinator.ExecutionPayloadFactory;

public class ExecutionPayloadPublisherGloas implements ExecutionPayloadPublisher {

  private static final Logger LOG = LogManager.getLogger();

  static final List<BiFunction<Spec, UInt64, Integer>> PAYLOAD_DELAYS_MILLIS =
      List.of(
          (spec, slot) ->
              spec.atSlot(slot).getForkChoiceUtil().getPayloadAttestationDueMillis().orElseThrow(),
          (spec, slot) ->
              spec.atSlot(slot).getForkChoiceUtil().getPayloadAttestationDueMillis().orElseThrow()
                  + 500,
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis() - 50,
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis(),
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis() + 1_500);

  static final AtomicInteger LAST_PAYLOAD_PUBLISHING_DELAY_INDEX = new AtomicInteger(0);
  static final AtomicInteger LAST_COLUMN_PUBLISHING_DELAY_INDEX = new AtomicInteger(0);

  // fraction of data column sidecars whose publishing is delayed when late column publishing is
  // enabled
  static final double DELAYED_COLUMN_FRACTION = 0.8;

  private final Spec spec;
  private final ExecutionPayloadFactory executionPayloadFactory;
  private final ExecutionPayloadGossipChannel executionPayloadGossipChannel;
  private final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel;
  private final ExecutionPayloadManager executionPayloadManager;
  private final boolean isLatePayloadPublishingEnabled;
  private final boolean isLateColumnPublishingEnabled;

  public ExecutionPayloadPublisherGloas(
      final ExecutionPayloadFactory executionPayloadFactory,
      final ExecutionPayloadGossipChannel executionPayloadGossipChannel,
      final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel,
      final ExecutionPayloadManager executionPayloadManager) {
    this(
        null,
        executionPayloadFactory,
        executionPayloadGossipChannel,
        dataColumnSidecarGossipChannel,
        executionPayloadManager,
        false,
        false);
  }

  public ExecutionPayloadPublisherGloas(
      final Spec spec,
      final ExecutionPayloadFactory executionPayloadFactory,
      final ExecutionPayloadGossipChannel executionPayloadGossipChannel,
      final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel,
      final ExecutionPayloadManager executionPayloadManager,
      final boolean isLatePayloadPublishingEnabled,
      final boolean isLateColumnPublishingEnabled) {
    this.spec = spec;
    this.executionPayloadFactory = executionPayloadFactory;
    this.executionPayloadGossipChannel = executionPayloadGossipChannel;
    this.dataColumnSidecarGossipChannel = dataColumnSidecarGossipChannel;
    this.executionPayloadManager = executionPayloadManager;
    this.isLatePayloadPublishingEnabled = isLatePayloadPublishingEnabled;
    this.isLateColumnPublishingEnabled = isLateColumnPublishingEnabled;
  }

  @Override
  public SafeFuture<PublishSignedExecutionPayloadResult> publishSignedExecutionPayload(
      final SignedExecutionPayloadEnvelope signedExecutionPayload) {
    return executionPayloadManager
        .validateAndImportExecutionPayload(signedExecutionPayload)
        .thenApply(
            result -> {
              final Bytes32 beaconBlockRoot = signedExecutionPayload.getBeaconBlockRoot();
              if (result.isAccept()) {
                // we publish the execution payload (and data column sidecars) after passing gossip
                // validation
                delay(signedExecutionPayload)
                    .thenRun(
                        () ->
                            publishExecutionPayloadAndDataColumnSidecars(
                                signedExecutionPayload,
                                executionPayloadFactory.createDataColumnSidecars(
                                    signedExecutionPayload)))
                    .finishError(LOG);
                return PublishSignedExecutionPayloadResult.success(beaconBlockRoot);
              }
              return PublishSignedExecutionPayloadResult.rejected(
                  beaconBlockRoot,
                  "Failed gossip validation"
                      + result.getDescription().map(description -> ": " + description).orElse(""));
            });
  }

  private SafeFuture<Void> delay(final SignedExecutionPayloadEnvelope signedExecutionPayload) {
    if (!isLatePayloadPublishingEnabled) {
      return SafeFuture.COMPLETE;
    }

    final UInt64 slot = signedExecutionPayload.getSlot();
    final int delayMillis =
        PAYLOAD_DELAYS_MILLIS
            .get(
                LAST_PAYLOAD_PUBLISHING_DELAY_INDEX.getAndUpdate(
                    i -> (i + 1) % PAYLOAD_DELAYS_MILLIS.size()))
            .apply(spec, slot);
    LOG.info("delaying execution payload publishing for slot {} by {} millis", slot, delayMillis);
    return new SafeFuture<Void>()
        .orTimeout(delayMillis, TimeUnit.MILLISECONDS)
        .exceptionally(ignore -> null)
        .toVoid();
  }

  private SafeFuture<Void> delayColumnPublishing(final UInt64 slot, final int delayedCount) {
    final int delayMillis =
        PAYLOAD_DELAYS_MILLIS
            .get(
                LAST_COLUMN_PUBLISHING_DELAY_INDEX.getAndUpdate(
                    i -> (i + 1) % PAYLOAD_DELAYS_MILLIS.size()))
            .apply(spec, slot);
    LOG.info(
        "delaying publishing of {} column sidecars for slot {} by {} millis",
        delayedCount,
        slot,
        delayMillis);
    return new SafeFuture<Void>()
        .orTimeout(delayMillis, TimeUnit.MILLISECONDS)
        .exceptionally(ignore -> null)
        .toVoid();
  }

  private void publishExecutionPayloadAndDataColumnSidecars(
      final SignedExecutionPayloadEnvelope signedExecutionPayload,
      final SafeFuture<List<DataColumnSidecar>> dataColumnSidecarsFuture) {
    executionPayloadGossipChannel.publishExecutionPayload(signedExecutionPayload).finishError(LOG);
    dataColumnSidecarsFuture.thenAccept(this::publishDataColumnSidecars).finishError(LOG);
  }

  private void publishDataColumnSidecars(final List<DataColumnSidecar> dataColumnSidecars) {
    if (!isLateColumnPublishingEnabled || dataColumnSidecars.isEmpty()) {
      dataColumnSidecarGossipChannel.publishDataColumnSidecars(
          dataColumnSidecars, RemoteOrigin.LOCAL_PROPOSAL);
      return;
    }

    // delay publishing of 80% of the data column sidecars, publish the rest immediately
    final int delayedCount = (int) Math.ceil(dataColumnSidecars.size() * DELAYED_COLUMN_FRACTION);
    final List<DataColumnSidecar> delayedDataColumnSidecars =
        dataColumnSidecars.subList(0, delayedCount);
    final List<DataColumnSidecar> immediateDataColumnSidecars =
        dataColumnSidecars.subList(delayedCount, dataColumnSidecars.size());

    dataColumnSidecarGossipChannel.publishDataColumnSidecars(
        immediateDataColumnSidecars, RemoteOrigin.LOCAL_PROPOSAL);
    delayColumnPublishing(dataColumnSidecars.getFirst().getSlot(), delayedCount)
        .thenRun(
            () ->
                dataColumnSidecarGossipChannel.publishDataColumnSidecars(
                    delayedDataColumnSidecars, RemoteOrigin.LOCAL_PROPOSAL))
        .finishError(LOG);
  }
}
