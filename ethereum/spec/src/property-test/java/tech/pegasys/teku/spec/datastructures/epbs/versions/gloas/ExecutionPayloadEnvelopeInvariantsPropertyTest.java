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

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

public class ExecutionPayloadEnvelopeInvariantsPropertyTest {

  @Property
  void extractSlotFromExecutionPayloadEnvelope(
      @ForAll(supplier = ExecutionPayloadEnvelopeSupplier.class)
          final ExecutionPayloadEnvelope executionPayloadEnvelope) {
    assertThat(
            ExecutionPayloadEnvelopeInvariants.extractExecutionPayloadEnvelopeSlot(
                executionPayloadEnvelope.sszSerialize()))
        .isEqualTo(executionPayloadEnvelope.getSlot());
  }

  @Property
  void extractSlotFromBlindedExecutionPayloadEnvelope(
      @ForAll(supplier = BlindedExecutionPayloadEnvelopeSupplier.class)
          final BlindedExecutionPayloadEnvelope blindedExecutionPayloadEnvelope) {
    assertThat(
            ExecutionPayloadEnvelopeInvariants.extractBlindedExecutionPayloadEnvelopeSlot(
                blindedExecutionPayloadEnvelope.sszSerialize()))
        .isEqualTo(blindedExecutionPayloadEnvelope.getSlot());
  }
}
