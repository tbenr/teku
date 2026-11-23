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
import static tech.pegasys.teku.spec.config.SpecConfigGloas.BUILDER_INDEX_SELF_BUILD;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ExecutionPayloadBidSchemaTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final UInt64 slot = UInt64.ONE;
  private final SchemaDefinitionsGloas schemaDefinitions =
      SchemaDefinitionsGloas.required(spec.atSlot(slot).getSchemaDefinitions());
  private final ExecutionPayloadBidSchema schema = schemaDefinitions.getExecutionPayloadBidSchema();

  @Test
  void createLocalSelfBuiltBid_shouldCommitToThePayload() {
    final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload(slot);
    final Bytes32 parentBlockRoot = dataStructureUtil.randomBytes32();
    final Bytes32 executionRequestsRoot = dataStructureUtil.randomBytes32();

    final ExecutionPayloadBid bid =
        schema.createLocalSelfBuiltBid(
            parentBlockRoot,
            slot,
            payload,
            schemaDefinitions.getBlobKzgCommitmentsSchema().of(),
            executionRequestsRoot);

    assertThat(bid.getPayloadRoot()).isEqualTo(payload.hashTreeRoot());
    assertThat(bid.getBlockHash()).isEqualTo(payload.getBlockHash());
    assertThat(bid.getParentBlockHash()).isEqualTo(payload.getParentHash());
    assertThat(bid.getParentBlockRoot()).isEqualTo(parentBlockRoot);
    assertThat(bid.getBuilderIndex()).isEqualTo(BUILDER_INDEX_SELF_BUILD);
    assertThat(bid.getExecutionRequestsRoot()).isEqualTo(executionRequestsRoot);
  }
}
