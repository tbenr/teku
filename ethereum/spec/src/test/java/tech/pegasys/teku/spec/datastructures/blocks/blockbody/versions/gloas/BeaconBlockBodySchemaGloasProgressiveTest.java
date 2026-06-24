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

package tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.gloas;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveListSchema;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodySchema;

class BeaconBlockBodySchemaGloasProgressiveTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final BeaconBlockBodySchema<?> bodySchema =
      spec.getGenesisSchemaDefinitions().getBeaconBlockBodySchema();

  @Test
  void operationListsAreProgressive() {
    assertThat(bodySchema.getProposerSlashingsSchema())
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(bodySchema.getAttesterSlashingsSchema())
        .isInstanceOf(SszProgressiveListSchema.class);
    assertThat(bodySchema.getAttestationsSchema()).isInstanceOf(SszProgressiveListSchema.class);
    assertThat(bodySchema.getDepositsSchema()).isInstanceOf(SszProgressiveListSchema.class);
    assertThat(bodySchema.getVoluntaryExitsSchema()).isInstanceOf(SszProgressiveListSchema.class);
  }

  @Test
  void bodyIsRawUnbounded() {
    assertThat(bodySchema.getSszLengthBounds().isUnbounded()).isTrue();
  }

  @Test
  void emptyBodySszRoundTrip() {
    final BeaconBlockBody body = bodySchema.createEmpty();
    final Bytes ssz = body.sszSerialize();
    final BeaconBlockBody deserialized = bodySchema.sszDeserialize(ssz);
    assertThat(deserialized.hashTreeRoot()).isEqualTo(body.hashTreeRoot());
  }
}
