/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.spec.datastructures.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;

public class AttestationPropertyTest {
  @Property
  void roundTrip(@ForAll(supplier = AttestationSupplier.class) final Attestation attestation)
      throws JsonProcessingException {
    final Attestation.AttestationSchema schema = attestation.getSchema();
    final DeserializableTypeDefinition<Attestation> typeDefinition = schema.getJsonTypeDefinition();

    // Round-trip SSZ serialization.
    final Bytes ssz = attestation.sszSerialize();
    final Attestation fromSsz = schema.sszDeserialize(ssz);
    assertThat(fromSsz).isEqualTo(attestation);

    // Round-trip JSON serialization.
    final String json = JsonUtil.serialize(attestation, typeDefinition);
    final Attestation fromJson = JsonUtil.parse(json, typeDefinition);
    assertThat(fromJson).isEqualTo(attestation);
  }
}
