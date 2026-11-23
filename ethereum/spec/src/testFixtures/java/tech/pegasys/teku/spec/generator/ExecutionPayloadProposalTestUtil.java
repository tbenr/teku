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

package tech.pegasys.teku.spec.generator;

import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockAndState;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.logic.common.util.ExecutionPayloadProposalUtil.ExecutionPayloadProposalData;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;

public class ExecutionPayloadProposalTestUtil {

  private final Spec spec;

  public ExecutionPayloadProposalTestUtil(final Spec spec) {
    this.spec = spec;
  }

  public ExecutionPayloadEnvelope createExecutionPayload(
      final UInt64 newSlot,
      final BeaconBlockAndState blockAndState,
      final ExecutionPayloadProposalData executionPayloadProposalData) {
    return SchemaDefinitionsGloas.required(spec.atSlot(newSlot).getSchemaDefinitions())
        .getExecutionPayloadEnvelopeSchema()
        .create(
            executionPayloadProposalData.executionPayload(),
            executionPayloadProposalData.executionRequests(),
            blockAndState.getRoot(),
            blockAndState.getBlock().getParentRoot());
  }
}
