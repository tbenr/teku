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

package tech.pegasys.teku.spec.logic.versions.gloas.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.Transaction;
import tech.pegasys.teku.spec.datastructures.execution.versions.gloas.ExecutionPayloadGloas;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.spec.logic.common.execution.ExecutionPayloadVerificationException;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;

class ExecutionPayloadVerifierGloasTest {
  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final ChainBuilder chainBuilder = ChainBuilder.create(spec);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryVerificationShouldRequirePostBlockState(final boolean advanceState)
      throws Exception {
    chainBuilder.generateGenesis();
    final UInt64 checkpointSlot = spec.computeStartSlotAtEpoch(UInt64.ONE);
    final BeaconState postState =
        chainBuilder.generateBlockAtSlot(checkpointSlot.minus(1)).getState();
    final ExecutionPayloadEnvelope envelope =
        chainBuilder.getExecutionPayloadAtSlot(postState.getSlot()).orElseThrow();
    final BeaconState state =
        advanceState ? spec.processSlots(postState, checkpointSlot) : postState;

    assertDoesNotThrow(
        () ->
            spec.getExecutionPayloadVerifier(envelope.getSlot())
                .verifyCheckpointAnchorExecutionPayloadEnvelope(
                    envelope, AnchorPoint.fromInitialState(spec, state), Optional.empty()));

    if (advanceState) {
      assertThatThrownBy(
              () ->
                  spec.getExecutionPayloadVerifier(envelope.getSlot())
                      .verifyExecutionPayloadEnvelope(envelope, state, Optional.empty()))
          .isInstanceOf(ExecutionPayloadVerificationException.class)
          .hasMessageContaining("slot");
      return;
    }
    assertDoesNotThrow(
        () ->
            spec.getExecutionPayloadVerifier(envelope.getSlot())
                .verifyExecutionPayloadEnvelope(envelope, state, Optional.empty()));
  }

  @Test
  void shouldRejectEnvelopeWhosePayloadRootDoesNotMatchTheBid() throws Exception {
    chainBuilder.generateGenesis();
    final SignedBlockAndState block = chainBuilder.generateBlockAtSlot(1);
    final ExecutionPayloadEnvelope envelope =
        chainBuilder.getExecutionPayloadAtSlot(block.getSlot()).orElseThrow();
    // Same block hash, prev randao, gas limit, parent hash, timestamp and withdrawals, but another
    // payload: only the payload root tells them apart
    final ExecutionPayloadEnvelope tamperedEnvelope =
        SchemaDefinitionsGloas.required(spec.atSlot(envelope.getSlot()).getSchemaDefinitions())
            .getExecutionPayloadEnvelopeSchema()
            .create(
                withExtraData(envelope.getPayload(), Bytes.fromHexString("0xdeadbeef")),
                envelope.getExecutionRequests(),
                envelope.getBeaconBlockRoot(),
                envelope.getParentBeaconBlockRoot());

    assertDoesNotThrow(
        () ->
            spec.getExecutionPayloadVerifier(envelope.getSlot())
                .verifyExecutionPayloadEnvelope(envelope, block.getState(), Optional.empty()));
    assertThatThrownBy(
            () ->
                spec.getExecutionPayloadVerifier(envelope.getSlot())
                    .verifyExecutionPayloadEnvelope(
                        tamperedEnvelope, block.getState(), Optional.empty()))
        .isInstanceOf(ExecutionPayloadVerificationException.class)
        .hasMessage(
            "Payload root of the committed bid is not consistent with the root of the payload");
  }

  private ExecutionPayload withExtraData(final ExecutionPayload payload, final Bytes extraData) {
    final ExecutionPayloadGloas payloadGloas = ExecutionPayloadGloas.required(payload);
    return SchemaDefinitionsGloas.required(
            spec.atSlot(payloadGloas.getSlotNumber()).getSchemaDefinitions())
        .getExecutionPayloadSchema()
        .createExecutionPayload(
            builder ->
                builder
                    .parentHash(payloadGloas.getParentHash())
                    .feeRecipient(payloadGloas.getFeeRecipient())
                    .stateRoot(payloadGloas.getStateRoot())
                    .receiptsRoot(payloadGloas.getReceiptsRoot())
                    .logsBloom(payloadGloas.getLogsBloom())
                    .prevRandao(payloadGloas.getPrevRandao())
                    .blockNumber(payloadGloas.getBlockNumber())
                    .gasLimit(payloadGloas.getGasLimit())
                    .gasUsed(payloadGloas.getGasUsed())
                    .timestamp(payloadGloas.getTimestamp())
                    .extraData(extraData)
                    .baseFeePerGas(payloadGloas.getBaseFeePerGas())
                    .blockHash(payloadGloas.getBlockHash())
                    .transactions(
                        payloadGloas.getTransactions().stream().map(Transaction::getBytes).toList())
                    .withdrawals(payloadGloas.getWithdrawals()::asList)
                    .blobGasUsed(payloadGloas::getBlobGasUsed)
                    .excessBlobGas(payloadGloas::getExcessBlobGas)
                    .blockAccessList(() -> payloadGloas.getBlockAccessList().getBytes())
                    .slotNumber(payloadGloas::getSlotNumber));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anchorVerificationShouldRejectWrongRootOrBlockBackedAnchor(final boolean blockBacked) {
    chainBuilder.generateGenesis();
    final SignedBlockAndState block = chainBuilder.generateBlockAtSlot(8);
    final ExecutionPayloadEnvelope envelope =
        chainBuilder.getExecutionPayloadAtSlot(block.getSlot()).orElseThrow();
    final AnchorPoint stateOnlyAnchor = AnchorPoint.fromInitialState(spec, block.getState());
    final AnchorPoint invalidAnchor =
        blockBacked
            ? AnchorPoint.create(spec, stateOnlyAnchor.getCheckpoint(), block)
            : AnchorPoint.fromInitialState(spec, chainBuilder.generateBlockAtSlot(16).getState());

    assertThatThrownBy(
            () ->
                spec.getExecutionPayloadVerifier(envelope.getSlot())
                    .verifyCheckpointAnchorExecutionPayloadEnvelope(
                        envelope, invalidAnchor, Optional.empty()))
        .isInstanceOf(ExecutionPayloadVerificationException.class)
        .hasMessageContaining("state-only Gloas checkpoint anchor");
  }
}
