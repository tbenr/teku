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

package tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.gloas;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveByteListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class BeaconStateSchemaGloasProgressiveTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final BeaconStateSchemaGloas schema =
      BeaconStateSchemaGloas.required(
          SchemaDefinitionsGloas.required(spec.getGenesisSchemaDefinitions())
              .getBeaconStateSchema());
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  @Test
  void stateContainerIsRawUnbounded() {
    assertThat(schema.getSszLengthBounds().isUnbounded()).isTrue();
  }

  @Test
  void commonListFieldsAreProgressive() {
    assertThat(childSchema(BeaconStateFields.VALIDATORS))
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(childSchema(BeaconStateFields.BALANCES))
        .isInstanceOf(SszProgressiveUInt64ListSchema.class);
    assertThat(childSchema(BeaconStateFields.ETH1_DATA_VOTES))
        .isInstanceOf(SszProgressiveListSchema.class);
  }

  @Test
  void inlineListFieldsAreProgressive() {
    assertThat(schema.getPreviousEpochParticipationSchema())
        .isInstanceOf(SszProgressiveByteListSchema.class);
    assertThat(schema.getCurrentEpochParticipationSchema())
        .isInstanceOf(SszProgressiveByteListSchema.class);
    assertThat(schema.getInactivityScoresSchema())
        .isInstanceOf(SszProgressiveUInt64ListSchema.class);
    assertThat(schema.getBuildersSchema()).isInstanceOf(SszProgressiveListSchema.class);
  }

  @Test
  void registryBackedListFieldsAreProgressive() {
    assertThat(schema.getPendingDepositsSchema()).isInstanceOf(SszProgressiveListSchema.class);
    assertThat(schema.getPendingPartialWithdrawalsSchema())
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(schema.getPendingConsolidationsSchema())
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(schema.getBuilderPendingWithdrawalsSchema())
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(childSchema(BeaconStateFields.HISTORICAL_SUMMARIES))
        .isInstanceOf(SszProgressiveListSchema.class);
  }

  @Test
  void emptyStateSszRoundTrip() {
    final BeaconState state = schema.createEmpty();
    final Bytes ssz = state.sszSerialize();
    final BeaconState deserialized = schema.sszDeserialize(ssz);
    assertThat(deserialized.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
  }

  @Test
  void populatedStateSszRoundTrip() {
    final BeaconState state = dataStructureUtil.randomBeaconState(10);
    final Bytes ssz = state.sszSerialize();
    final BeaconState deserialized = schema.sszDeserialize(ssz);
    assertThat(deserialized.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
    assertThat(deserialized.getValidators().size()).isEqualTo(state.getValidators().size());
    assertThat(deserialized.getBalances().size()).isEqualTo(state.getBalances().size());
  }

  private SszSchema<?> childSchema(final BeaconStateFields field) {
    return schema.getChildSchema(schema.getFieldIndex(field.getSszFieldName()));
  }
}
