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

package tech.pegasys.teku.spec.datastructures.operations.versions.gloas;

import tech.pegasys.teku.infrastructure.ssz.schema.ProgressiveSchemaUtils;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveUInt64ListSchema;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.operations.IndexedAttestationSchema;
import tech.pegasys.teku.spec.datastructures.type.SszSignatureSchema;

/**
 * Gloas indexed attestation schema: a progressive (EIP-7916) container whose {@code
 * attesting_indices} is a progressive UInt64 list.
 */
public class IndexedAttestationGloasSchema extends IndexedAttestationSchema {

  private static final boolean[] ACTIVE_FIELDS = ProgressiveSchemaUtils.allActive(3);

  public IndexedAttestationGloasSchema(final String containerName) {
    super(
        containerName,
        ACTIVE_FIELDS,
        namedSchema("attesting_indices", SszProgressiveUInt64ListSchema.create()),
        namedSchema("data", AttestationData.SSZ_SCHEMA),
        namedSchema("signature", SszSignatureSchema.INSTANCE));
  }
}
