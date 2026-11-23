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

package tech.pegasys.teku.validator.remote.typedef.handlers;

import static java.util.Collections.emptyMap;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_ACCEPTED;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_NOT_FOUND;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_VERSION;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.PARAM_BROADCAST_VALIDATION;
import static tech.pegasys.teku.validator.remote.apiclient.ValidatorApiMethod.SEND_EXECUTION_PAYLOAD_ENVELOPE;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult.Outcome;
import tech.pegasys.teku.validator.remote.typedef.ResponseHandler;

public class PublishSignedExecutionPayloadRequest extends AbstractTypeDefRequest {

  private final ResponseHandler<Outcome> responseHandler =
      new ResponseHandler<Outcome>()
          .withHandler(SC_OK, (request, response) -> Optional.of(Outcome.PUBLISHED))
          .withHandler(
              SC_ACCEPTED, (request, response) -> Optional.of(Outcome.PUBLISHED_NOT_IMPORTED))
          // the beacon node has not seen the beacon block, so it has broadcast nothing
          .withHandler(SC_NOT_FOUND, (request, response) -> Optional.of(Outcome.BLOCK_NOT_FOUND));

  private final Spec spec;

  public PublishSignedExecutionPayloadRequest(
      final Spec spec, final HttpUrl baseEndpoint, final OkHttpClient okHttpClient) {
    super(baseEndpoint, okHttpClient);
    this.spec = spec;
  }

  /** The envelope is sent along with its blobs and KZG proofs. */
  public PublishSignedExecutionPayloadResult submit(
      final ExecutionPayloadEnvelopeContents executionPayloadEnvelopeContents,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {

    final Map<String, String> queryParams = getQueryParams(broadcastValidationLevel);

    final DeserializableTypeDefinition<ExecutionPayloadEnvelopeContents> typeDefinition =
        spec.atSlot(executionPayloadEnvelopeContents.getSlot())
            .getSchemaDefinitions()
            .toVersionGloas()
            .orElseThrow()
            .getExecutionPayloadEnvelopeContentsSchema()
            .getJsonTypeDefinition();

    return createResult(
        postJson(
            SEND_EXECUTION_PAYLOAD_ENVELOPE,
            emptyMap(),
            queryParams,
            getHeaders(executionPayloadEnvelopeContents.getSlot()),
            executionPayloadEnvelopeContents,
            typeDefinition,
            responseHandler),
        executionPayloadEnvelopeContents.getBeaconBlockRoot());
  }

  private PublishSignedExecutionPayloadResult createResult(
      final Optional<Outcome> maybeOutcome, final Bytes32 beaconBlockRoot) {
    return switch (maybeOutcome.orElse(Outcome.PUBLISHED_NOT_IMPORTED)) {
      case PUBLISHED -> PublishSignedExecutionPayloadResult.success(beaconBlockRoot);
      case PUBLISHED_NOT_IMPORTED ->
          PublishSignedExecutionPayloadResult.notImported(beaconBlockRoot, "UNKNOWN");
      case BLOCK_NOT_FOUND -> PublishSignedExecutionPayloadResult.blockNotFound(beaconBlockRoot);
      case REJECTED ->
          throw new IllegalStateException("A rejected execution payload is reported as an error");
    };
  }

  private Map<String, String> getHeaders(final UInt64 slot) {
    final SpecMilestone milestone = spec.atSlot(slot).getMilestone();
    return Map.of(HEADER_CONSENSUS_VERSION, milestone.lowerCaseName());
  }

  private Map<String, String> getQueryParams(
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    return broadcastValidationLevel
        .map(level -> Map.of(PARAM_BROADCAST_VALIDATION, level.name().toLowerCase(Locale.ROOT)))
        .orElse(emptyMap());
  }
}
