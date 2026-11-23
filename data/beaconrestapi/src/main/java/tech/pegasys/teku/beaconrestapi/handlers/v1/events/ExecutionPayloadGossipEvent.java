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

package tech.pegasys.teku.beaconrestapi.handlers.v1.events;

import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.BYTES32_TYPE;
import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.UINT64_TYPE;

import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.json.types.SerializableTypeDefinition;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;

public class ExecutionPayloadGossipEvent
    extends Event<ExecutionPayloadGossipEvent.ExecutionPayloadData> {

  private static final SerializableTypeDefinition<ExecutionPayloadData>
      EXECUTION_PAYLOAD_GOSSIP_EVENT_TYPE =
          SerializableTypeDefinition.object(ExecutionPayloadData.class)
              .name("ExecutionPayloadGossipEvent")
              .withField("slot", UINT64_TYPE, ExecutionPayloadData::slot)
              .withField("builder_index", UINT64_TYPE, ExecutionPayloadData::builderIndex)
              .withField("block_hash", BYTES32_TYPE, ExecutionPayloadData::blockHash)
              .withField("payload_root", BYTES32_TYPE, ExecutionPayloadData::payloadRoot)
              .withField("block_root", BYTES32_TYPE, ExecutionPayloadData::blockRoot)
              .build();

  /**
   * @param bid the execution payload bid of the envelope's beacon block, which names the builder
   *     and commits to the payload root
   */
  ExecutionPayloadGossipEvent(
      final ExecutionPayloadEnvelope executionPayload, final ExecutionPayloadBid bid) {
    super(
        EXECUTION_PAYLOAD_GOSSIP_EVENT_TYPE,
        new ExecutionPayloadData(
            executionPayload.getSlot(),
            bid.getBuilderIndex(),
            executionPayload.getPayload().getBlockHash(),
            bid.getPayloadRoot(),
            executionPayload.getBeaconBlockRoot()));
  }

  public record ExecutionPayloadData(
      UInt64 slot,
      UInt64 builderIndex,
      Bytes32 blockHash,
      Bytes32 payloadRoot,
      Bytes32 blockRoot) {}
}
