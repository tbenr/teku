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

package tech.pegasys.teku.networking.eth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveBitlistSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.networking.eth2.gossip.NetworkSszLengthBoundsProvider;

class SszNetworkSchemaBoundsTest {

  private final SszSchema<?> unboundedSchema =
      SszProgressiveListSchema.create(SszPrimitiveSchemas.UINT64_SCHEMA);
  private final SszSchema<?> finiteSchema =
      SszListSchema.create(SszPrimitiveSchemas.UINT64_SCHEMA, 10);

  @Test
  void appliesOverrideToUnboundedSchema() {
    assertThat(
            SszNetworkSchemaBounds.resolve("test", unboundedSchema, OptionalInt.of(1024))
                .getMaxBytes())
        .isEqualTo(1024);
  }

  @Test
  void rejectsOverrideForFiniteSchema() {
    assertThatThrownBy(
            () -> SszNetworkSchemaBounds.resolve("test", finiteSchema, OptionalInt.of(1024)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only allowed for unbounded raw SSZ schemas");
  }

  @Test
  void rejectsAnyOverrideForFiniteSchema() {
    assertThatThrownBy(
            () -> SszNetworkSchemaBounds.resolve("test", finiteSchema, OptionalInt.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverrideBelowUnboundedMinimum() {
    // Progressive bitlist has a 1-byte minimum (boundary bit), so an override of 0 is invalid.
    assertThatThrownBy(
            () ->
                SszNetworkSchemaBounds.resolve(
                    "test", new SszProgressiveBitlistSchema(), OptionalInt.of(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("below the raw minimum SSZ size");
  }

  @Test
  void usesRawBoundsWhenNoOverrideForFiniteSchema() {
    assertThat(SszNetworkSchemaBounds.resolve("test", finiteSchema, OptionalInt.empty()))
        .isEqualTo(finiteSchema.getSszLengthBounds());
  }

  @Test
  void usesRawBoundsWhenNoOverrideForUnboundedSchema() {
    assertThat(SszNetworkSchemaBounds.resolve("test", unboundedSchema, OptionalInt.empty()))
        .isEqualTo(unboundedSchema.getSszLengthBounds());
  }

  @Test
  void rawProviderUsesRawBounds() {
    final NetworkSszLengthBoundsProvider provider = NetworkSszLengthBoundsProvider.raw();
    assertThat(provider.getBounds("test", finiteSchema))
        .isEqualTo(finiteSchema.getSszLengthBounds());
    assertThat(provider.getBounds("test", unboundedSchema).isUnbounded()).isTrue();
  }
}
