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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_ACCEPTED;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_NOT_FOUND;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.beaconrestapi.AbstractMigratedBeaconHandlerTest;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;

public class PostExecutionPayloadEnvelopeTest extends AbstractMigratedBeaconHandlerTest {

  @BeforeEach
  void setup() {
    setSpec(TestSpecFactory.createMinimalGloas());
    setHandler(new PostExecutionPayloadEnvelope(validatorDataProvider, schemaDefinitionCache));
  }

  @Test
  void shouldReturnOkIfSuccess() throws Exception {
    final ExecutionPayloadEnvelopeContents envelope =
        contentsFor(dataStructureUtil.randomExecutionPayloadEnvelope(1));
    final PublishSignedExecutionPayloadResult successResult =
        PublishSignedExecutionPayloadResult.success(envelope.getBeaconBlockRoot());

    request.setRequestBody(envelope);
    when(validatorDataProvider.publishSignedExecutionPayload(
            any(ExecutionPayloadEnvelopeContents.class), any()))
        .thenReturn(SafeFuture.completedFuture(successResult));

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_OK);
    assertThat(request.getResponseBody()).isNull();
  }

  @Test
  void shouldReturnAcceptedIfPublishedButRejected() throws Exception {
    final ExecutionPayloadEnvelopeContents envelope =
        contentsFor(dataStructureUtil.randomExecutionPayloadEnvelope(1));
    final PublishSignedExecutionPayloadResult failResult =
        PublishSignedExecutionPayloadResult.notImported(
            envelope.getBeaconBlockRoot(), "Invalid payload");

    request.setRequestBody(envelope);
    when(validatorDataProvider.publishSignedExecutionPayload(
            any(ExecutionPayloadEnvelopeContents.class), any()))
        .thenReturn(SafeFuture.completedFuture(failResult));

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_ACCEPTED);
    assertThat(request.getResponseBody()).isNull();
  }

  @Test
  void shouldReturnBadRequestIfRejectedAndNotPublished() throws Exception {
    final ExecutionPayloadEnvelopeContents envelope =
        contentsFor(dataStructureUtil.randomExecutionPayloadEnvelope(1));
    final PublishSignedExecutionPayloadResult failResult =
        PublishSignedExecutionPayloadResult.rejected(envelope.getBeaconBlockRoot(), "oopsy");

    request.setRequestBody(envelope);
    when(validatorDataProvider.publishSignedExecutionPayload(
            any(ExecutionPayloadEnvelopeContents.class), any()))
        .thenReturn(SafeFuture.completedFuture(failResult));

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_BAD_REQUEST);
    assertThat(request.getResponseBodyAsJson(handler))
        .isEqualTo("{\"code\":400,\"message\":\"oopsy\"}");
  }

  @Test
  void shouldReturnBadRequestIfInvalidBroadcastValidation() throws Exception {
    final ExecutionPayloadEnvelopeContents envelope =
        contentsFor(dataStructureUtil.randomExecutionPayloadEnvelope(1));

    request.setRequestBody(envelope);
    request.setOptionalQueryParameter("broadcast_validation", "invalid_value");

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_BAD_REQUEST);
    assertThat(request.getResponseBodyAsJson(handler))
        .isEqualTo(
            "{\"code\":400,\"message\":\"Invalid value for broadcast_validation: Unknown enum value: invalid_value\"}");
  }

  @Test
  void shouldReturnNotFoundIfBlockHasNotBeenSeen() throws Exception {
    final ExecutionPayloadEnvelopeContents contents =
        contentsFor(dataStructureUtil.randomExecutionPayloadEnvelope(1));
    final PublishSignedExecutionPayloadResult notFoundResult =
        PublishSignedExecutionPayloadResult.blockNotFound(contents.getBeaconBlockRoot());

    request.setRequestBody(contents);
    when(validatorDataProvider.publishSignedExecutionPayload(
            any(ExecutionPayloadEnvelopeContents.class), any()))
        .thenReturn(SafeFuture.completedFuture(notFoundResult));

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_NOT_FOUND);
    assertThat(request.getResponseBodyAsJson(handler))
        .isEqualTo("{\"code\":404,\"message\":\"Beacon block not found\"}");
  }

  private ExecutionPayloadEnvelopeContents contentsFor(final ExecutionPayloadEnvelope envelope) {
    return schemaDefinitionCache
        .getSchemaDefinition(SpecMilestone.GLOAS)
        .toVersionGloas()
        .orElseThrow()
        .getExecutionPayloadEnvelopeContentsSchema()
        .create(envelope, List.of(), List.of());
  }
}
