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

package tech.pegasys.teku.spec.datastructures.epbs.versions.gloas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionPayloadEnvelopeTest {
  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SchemaDefinitionsGloas schemaDefinitions =
      SchemaDefinitionsGloas.required(spec.getGenesisSchemaDefinitions());

  @Test
  void blindedPayloadHeaderRootShouldEqualPayloadRoot() {
    // Backfill checks the root of the blinded payload header against the bid's payload root, which
    // commits to the root of the full payload
    final ExecutionPayload payloadWithTransactions =
        dataStructureUtil.randomExecutionPayload(UInt64.valueOf(10));
    final ExecutionPayload payloadWithoutTransactions =
        dataStructureUtil.randomExecutionPayload(
            UInt64.valueOf(11), builder -> builder.transactions(List.of()).withdrawals(List::of));
    for (final ExecutionPayload payload :
        List.of(payloadWithTransactions, payloadWithoutTransactions)) {
      final ExecutionPayloadHeader header =
          schemaDefinitions.getExecutionPayloadHeaderSchema().createFromExecutionPayload(payload);
      assertThat(header.hashTreeRoot()).isEqualTo(payload.hashTreeRoot());
    }
  }

  @Test
  void shouldBlindAndUnblind() {
    final ExecutionPayloadEnvelope originalExecutionPayloadEnvelope =
        dataStructureUtil.randomExecutionPayloadEnvelope(10);

    final BlindedExecutionPayloadEnvelope blindedExecutionPayloadEnvelope =
        originalExecutionPayloadEnvelope.blind(spec);
    assertThat(blindedExecutionPayloadEnvelope.hashTreeRoot())
        .isEqualTo(originalExecutionPayloadEnvelope.hashTreeRoot());
    assertThat(blindedExecutionPayloadEnvelope.getPayloadHeader().hashTreeRoot())
        .isEqualTo(originalExecutionPayloadEnvelope.getPayload().hashTreeRoot());
    assertThatNoException().isThrownBy(blindedExecutionPayloadEnvelope::sszSerialize);
    assertThatNoException()
        .isThrownBy(
            () ->
                JsonUtil.serialize(
                    blindedExecutionPayloadEnvelope,
                    schemaDefinitions
                        .getBlindedExecutionPayloadEnvelopeSchema()
                        .getJsonTypeDefinition()));

    final ExecutionPayloadEnvelope unblindedExecutionPayloadEnvelope =
        blindedExecutionPayloadEnvelope.unblind(
            schemaDefinitions, originalExecutionPayloadEnvelope.getPayload());
    assertThat(unblindedExecutionPayloadEnvelope.hashTreeRoot())
        .isEqualTo(originalExecutionPayloadEnvelope.hashTreeRoot());
    assertThat(unblindedExecutionPayloadEnvelope.sszSerialize())
        .isEqualTo(originalExecutionPayloadEnvelope.sszSerialize());
    assertThatNoException()
        .isThrownBy(
            () ->
                JsonUtil.serialize(
                    unblindedExecutionPayloadEnvelope,
                    schemaDefinitions.getExecutionPayloadEnvelopeSchema().getJsonTypeDefinition()));
  }
}
