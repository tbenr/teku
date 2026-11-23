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

package tech.pegasys.teku.validator.coordinator;

import java.util.List;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;

public interface ExecutionPayloadFactory {

  /**
   * The envelope revealing the execution payload this node built for {@code block}, with its blobs
   * and KZG proofs, or empty when the node holds no locally built payload committed to by the
   * block's bid.
   */
  SafeFuture<Optional<ExecutionPayloadEnvelopeContents>> createSelfBuiltExecutionPayload(
      BeaconBlock block);

  List<DataColumnSidecar> createDataColumnSidecars(
      ExecutionPayloadEnvelopeContents executionPayloadEnvelopeContents);
}
