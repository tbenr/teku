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

package tech.pegasys.teku.beaconrestapi.handlers.v1.beacon;

import static tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.ETH_CONSENSUS_VERSION_TYPE;
import static tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.PARAMETER_BROADCAST_VALIDATION;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_ACCEPTED;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_NOT_FOUND;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_UNSUPPORTED_MEDIA_TYPE;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_BEACON;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_VALIDATOR_REQUIRED;
import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.HTTP_ERROR_RESPONSE_TYPE;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import tech.pegasys.teku.api.ValidatorDataProvider;
import tech.pegasys.teku.beaconrestapi.BeaconRestApiTypes.BroadcastValidationParameter;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.restapi.endpoints.AsyncApiResponse;
import tech.pegasys.teku.infrastructure.restapi.endpoints.EndpointMetadata;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiEndpoint;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiRequest;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContentsSchema;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionCache;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;

public class PostExecutionPayloadEnvelope extends RestApiEndpoint {

  public static final String ROUTE = "/eth/v1/beacon/execution_payload_envelopes";
  private final ValidatorDataProvider validatorDataProvider;

  public PostExecutionPayloadEnvelope(
      final ValidatorDataProvider validatorDataProvider, final SchemaDefinitionCache schemaCache) {
    super(createEndpointMetadata(schemaCache));
    this.validatorDataProvider = validatorDataProvider;
  }

  private static EndpointMetadata createEndpointMetadata(final SchemaDefinitionCache schemaCache) {
    final ExecutionPayloadEnvelopeContentsSchema executionPayloadEnvelopeContentsSchema =
        schemaCache
            .getSchemaDefinition(SpecMilestone.GLOAS)
            .toVersionGloas()
            .orElseThrow()
            .getExecutionPayloadEnvelopeContentsSchema();

    return EndpointMetadata.post(ROUTE)
        .operationId("publishExecutionPayloadEnvelope")
        .summary("Publish execution payload envelope")
        .description(
            """
            Instructs the beacon node to broadcast an execution payload envelope and its blobs to \
            the network. The request body is an ExecutionPayloadEnvelopeContents object: the \
            envelope along with blobs and KZG proofs. The envelope is not signed: the beacon block \
            with root beacon_block_root commits to every field of it. Before anything is broadcast, \
            the envelope is checked against the execution payload bid of that block and the blobs \
            and KZG proofs against the blob KZG commitments of that bid. An envelope the beacon \
            node has already seen is not broadcast again, but the blob data of the request still \
            is. A beacon block which has not been seen is awaited until the end of the envelope's \
            slot.""")
        .tags(TAG_BEACON, TAG_VALIDATOR_REQUIRED)
        .queryParam(PARAMETER_BROADCAST_VALIDATION)
        .headerRequired(
            ETH_CONSENSUS_VERSION_TYPE.withDescription(
                "The active consensus version to which the execution payload envelope being submitted belongs."))
        .requestBodyType(
            executionPayloadEnvelopeContentsSchema.getJsonTypeDefinition(),
            executionPayloadEnvelopeContentsSchema::sszDeserialize)
        .response(
            SC_OK,
            "The envelope was validated successfully and has been broadcast, by this request or before it. It has also been integrated into the beacon node's database.")
        .response(
            SC_ACCEPTED,
            "The envelope could not be integrated into the beacon node's database as it failed validation, but was successfully broadcast.")
        .response(
            SC_BAD_REQUEST,
            """
            The envelope object is invalid, it or its blob data does not match the execution \
            payload bid of its beacon block, or broadcast validation failed.""",
            HTTP_ERROR_RESPONSE_TYPE)
        .response(
            SC_NOT_FOUND,
            "The beacon block of the envelope has not been seen.",
            HTTP_ERROR_RESPONSE_TYPE)
        .response(SC_UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", HTTP_ERROR_RESPONSE_TYPE)
        .withInternalErrorResponse()
        .withServiceUnavailableResponse()
        .build();
  }

  @Override
  public void handleRequest(final RestApiRequest request) throws JsonProcessingException {
    final ExecutionPayloadEnvelopeContents executionPayloadEnvelopeContents =
        request.getRequestBody();
    final Optional<BroadcastValidationLevel> broadcastValidationLevel;
    try {
      broadcastValidationLevel =
          request
              .getOptionalQueryParameter(PARAMETER_BROADCAST_VALIDATION)
              .map(BroadcastValidationParameter::toInternal);
    } catch (final IllegalArgumentException e) {
      request.respondError(
          SC_BAD_REQUEST, "Invalid value for broadcast_validation: " + e.getMessage());
      return;
    }

    final SafeFuture<PublishSignedExecutionPayloadResult> future =
        validatorDataProvider.publishSignedExecutionPayload(
            executionPayloadEnvelopeContents, broadcastValidationLevel);
    request.respondAsync(future.thenApply(this::processPublishSignedExecutionPayloadResult));
  }

  private AsyncApiResponse processPublishSignedExecutionPayloadResult(
      final PublishSignedExecutionPayloadResult result) {
    final String rejectionReason = result.rejectionReason().orElse("");
    return switch (result.outcome()) {
      case PUBLISHED -> AsyncApiResponse.respondWithCode(SC_OK);
      case PUBLISHED_NOT_IMPORTED -> AsyncApiResponse.respondWithCode(SC_ACCEPTED);
      case REJECTED -> AsyncApiResponse.respondWithError(SC_BAD_REQUEST, rejectionReason);
      case BLOCK_NOT_FOUND -> AsyncApiResponse.respondWithError(SC_NOT_FOUND, rejectionReason);
    };
  }
}
