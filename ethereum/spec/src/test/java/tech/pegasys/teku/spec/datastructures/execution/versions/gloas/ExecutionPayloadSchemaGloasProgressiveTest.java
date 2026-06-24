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

package tech.pegasys.teku.spec.datastructures.execution.versions.gloas;

import static org.assertj.core.api.Assertions.assertThat;

import it.unimi.dsi.fastutil.longs.LongList;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveByteListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveListSchema;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ProgressiveTransactionSchema;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionPayloadSchemaGloasProgressiveTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final SchemaDefinitionsGloas schemaDefinitions =
      SchemaDefinitionsGloas.required(spec.getGenesisSchemaDefinitions());
  private final ExecutionPayloadSchemaGloas payloadSchema =
      schemaDefinitions.getExecutionPayloadSchema().toVersionGloasRequired();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  @Test
  void transactionsAndWithdrawalsAreProgressiveLists() {
    assertThat(payloadSchema.getTransactionsSchema()).isInstanceOf(SszProgressiveListSchema.class);
    assertThat(payloadSchema.getWithdrawalsSchema()).isInstanceOf(SszProgressiveListSchema.class);
  }

  @Test
  void extraDataAndBlockAccessListAreProgressiveByteLists() {
    assertThat(payloadSchema.getExtraDataSchema()).isInstanceOf(SszProgressiveByteListSchema.class);
    assertThat(payloadSchema.getBlockAccessListSchema())
        .isInstanceOf(SszProgressiveByteListSchema.class);
  }

  @Test
  void transactionElementSchemaIsProgressive() {
    assertThat(payloadSchema.getTransactionSchema())
        .isInstanceOf(ProgressiveTransactionSchema.class);
  }

  @Test
  void payloadSchemaIsRawUnbounded() {
    assertThat(payloadSchema.getSszLengthBounds().isUnbounded()).isTrue();
  }

  @Test
  void blindedNodeGeneralizedIndicesArePresent() {
    final LongList indices = payloadSchema.getBlindedNodeGeneralizedIndices();
    assertThat(indices.size()).isEqualTo(3);
    for (int i = 0; i < indices.size(); i++) {
      assertThat(indices.getLong(i)).isPositive();
    }
  }

  @Test
  void executionPayloadSszRoundTrip() {
    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();
    final Bytes ssz = payload.sszSerialize();
    final ExecutionPayload deserialized = payloadSchema.sszDeserialize(ssz);
    assertThat(deserialized.hashTreeRoot()).isEqualTo(payload.hashTreeRoot());
  }
}
