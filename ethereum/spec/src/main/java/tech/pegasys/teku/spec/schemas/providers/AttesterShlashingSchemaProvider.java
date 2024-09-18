/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.spec.schemas.providers;

import static tech.pegasys.teku.spec.SpecMilestone.DENEB;
import static tech.pegasys.teku.spec.SpecMilestone.PHASE0;
import static tech.pegasys.teku.spec.schemas.SchemaTypes.INDEXED_ATTESTATION_SCHEMA;

import java.util.Set;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashingSchema;
import tech.pegasys.teku.spec.datastructures.operations.versions.electra.AttesterSlashingElectraSchema;
import tech.pegasys.teku.spec.datastructures.operations.versions.phase0.AttesterSlashingPhase0Schema;
import tech.pegasys.teku.spec.schemas.AbstractSchemaProvider;
import tech.pegasys.teku.spec.schemas.SchemaRegistry;
import tech.pegasys.teku.spec.schemas.SchemaTypes;

public class AttesterShlashingSchemaProvider
    extends AbstractSchemaProvider<AttesterSlashingSchema<AttesterSlashing>> {

  public AttesterShlashingSchemaProvider() {
    super(SchemaTypes.ATTESTER_SLASHING_SCHEMA);
    addMilestoneMapping(PHASE0, DENEB);
  }

  @Override
  protected AttesterSlashingSchema<AttesterSlashing> createSchema(
      final SchemaRegistry registry,
      final SpecMilestone effectiveMilestone,
      final SpecConfig specConfig) {
    return switch (effectiveMilestone) {
      case PHASE0 ->
          new AttesterSlashingPhase0Schema(registry.get(INDEXED_ATTESTATION_SCHEMA))
              .castTypeToAttesterSlashingSchema();
      case ELECTRA ->
          new AttesterSlashingElectraSchema(registry.get(INDEXED_ATTESTATION_SCHEMA))
              .castTypeToAttesterSlashingSchema();
      default ->
          throw new IllegalArgumentException(
              "It is not supposed to create a specific version for " + effectiveMilestone);
    };
  }

  @Override
  public Set<SpecMilestone> getSupportedMilestones() {
    return ALL_MILESTONES;
  }
}