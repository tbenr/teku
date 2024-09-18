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

import static tech.pegasys.teku.spec.schemas.SchemaTypes.BLOB_KZG_COMMITMENTS_SCHEMA;

import java.util.Set;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.config.SpecConfigDeneb;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobKzgCommitmentsSchema;
import tech.pegasys.teku.spec.schemas.AbstractSchemaProvider;
import tech.pegasys.teku.spec.schemas.SchemaRegistry;

public class BlobKzgCommitmentsSchemaProvider
    extends AbstractSchemaProvider<BlobKzgCommitmentsSchema> {

  public BlobKzgCommitmentsSchemaProvider() {
    super(BLOB_KZG_COMMITMENTS_SCHEMA);
  }

  @Override
  protected BlobKzgCommitmentsSchema createSchema(
      final SchemaRegistry registry,
      final SpecMilestone effectiveMilestone,
      final SpecConfig specConfig) {
    return new BlobKzgCommitmentsSchema(SpecConfigDeneb.required(specConfig));
  }

  @Override
  public Set<SpecMilestone> getSupportedMilestones() {
    return FROM_DENEB;
  }
}