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

package tech.pegasys.teku.statetransition.validation;

import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;
import static tech.pegasys.teku.spec.config.Constants.VALID_BLOCK_SET_SIZE;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.reject;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.collections.LimitedMap;
import tech.pegasys.teku.infrastructure.collections.LimitedSet;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.constants.Domain;
import tech.pegasys.teku.spec.datastructures.execution.SignedExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.execution.versions.eip7732.ExecutionPayloadHeaderEip7732;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.client.RecentChainData;

@SuppressWarnings("unused")
public class ExecutionPayloadHeaderValidator {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final GossipValidationHelper gossipValidationHelper;
  private final RecentChainData recentChainData;

  private final Set<SlotAndBuilderIndex> receivedValidExecutionPayloadHeaderInfoSet =
      LimitedSet.createSynchronized(VALID_BLOCK_SET_SIZE);
  // EIP7732 TODO: not sure here what is the best value
  private final Map<SlotAndParentBlockHash, UInt64> highestBidValue =
      LimitedMap.createSynchronizedLRU(10);

  public ExecutionPayloadHeaderValidator(
      final Spec spec,
      final GossipValidationHelper gossipValidationHelper,
      final RecentChainData recentChainData) {
    this.spec = spec;
    this.gossipValidationHelper = gossipValidationHelper;
    this.recentChainData = recentChainData;
  }

  SafeFuture<InternalValidationResult> validate(final SignedExecutionPayloadHeader signedHeader) {
    final ExecutionPayloadHeaderEip7732 header =
        ExecutionPayloadHeaderEip7732.required(signedHeader.getMessage());
    final UInt64 builderIndex = header.getBuilderIndex();
    final SlotAndBuilderIndex slotAndBuilderIndex =
        new SlotAndBuilderIndex(header.getSlot(), builderIndex);
    /*
     * [IGNORE] this is the first signed bid seen with a valid signature from the given builder for
     * this slot.
     */
    if (receivedValidExecutionPayloadHeaderInfoSet.contains(slotAndBuilderIndex)) {
      return completedFuture(InternalValidationResult.IGNORE);
    }

    final SlotAndParentBlockHash slotAndParentBlockHash =
        new SlotAndParentBlockHash(header.getSlot(), header.getParentBlockHash());
    /*
     * [IGNORE] this bid is the highest value bid seen for the pair of the corresponding slot and the given parent block hash.
     */
    final UInt64 currentHighestBidValue =
        highestBidValue.computeIfAbsent(slotAndParentBlockHash, k -> UInt64.ZERO);
    if (header.getValue().isLessThanOrEqualTo(currentHighestBidValue)) {
      return completedFuture(InternalValidationResult.IGNORE);
    }

    final Optional<UInt64> maybeParentBlockSlot =
        gossipValidationHelper.getSlotForBlockRoot(header.getParentBlockRoot());
    if (maybeParentBlockSlot.isEmpty()) {
      LOG.trace(
          "ExecutionPayloadHeaderValidator: Parent block root does not exist. It will be saved for future processing");
      return completedFuture(InternalValidationResult.SAVE_FOR_FUTURE);
    }
    final UInt64 parentBlockSlot = maybeParentBlockSlot.get();

    return gossipValidationHelper
        .getParentStateInBlockEpoch(parentBlockSlot, header.getParentBlockRoot(), header.getSlot())
        .thenApply(
            maybeState -> {
              if (maybeState.isEmpty()) {
                LOG.trace(
                    "State wasn't available for parent block root {}", header.getParentBlockRoot());
                return InternalValidationResult.IGNORE;
              }
              final BeaconState state = maybeState.get();
              /*
               * [REJECT] The signed builder bid, header.builder_index is a valid, active, and non-slashed builder index in state.
               */
              final Validator builder = state.getValidators().get(builderIndex.intValue());
              final UInt64 epoch = spec.computeEpochAtSlot(state.getSlot());
              final boolean builderIsActive =
                  builder.getActivationEpoch().isLessThanOrEqualTo(epoch)
                      && epoch.isLessThan(builder.getExitEpoch());
              if (builderIsActive || builder.isSlashed()) {
                return reject("Builder is not active or is slashed");
              }
              /*
               * [IGNORE] The signed builder bid value, header.value, is less or equal than the builder's balance in state. i.e. MIN_BUILDER_BALANCE + header.value < state.builder_balances[header.builder_index].
               */
              if (header
                  .getValue()
                  .isGreaterThan(state.getBalances().get(builderIndex.intValue()).get())) {
                return InternalValidationResult.IGNORE;
              }
              /*
               * [IGNORE] header.parent_block_hash is the block hash of a known execution payload in fork choice.
               */
              final Optional<Bytes32> parentBlockHash =
                  recentChainData.getExecutionBlockHashForBlockRoot(header.getParentBlockRoot());
              if (parentBlockHash.isEmpty()
                  || header.getParentBlockHash().equals(parentBlockHash.get())) {
                return InternalValidationResult.IGNORE;
              }
              /*
               * [IGNORE] header.parent_block_root is the hash tree root of a known beacon block in fork choice.
               */
              final Optional<Boolean> parentBlockRootIsKnown =
                  recentChainData
                      .getForkChoiceStrategy()
                      .map(forkChoice -> forkChoice.contains(header.getParentBlockRoot()));
              if (parentBlockRootIsKnown.isEmpty() || !parentBlockRootIsKnown.get()) {
                return InternalValidationResult.IGNORE;
              }
              /*
               * [IGNORE] header.slot is the current slot or the next slot.
               */
              final Optional<UInt64> currentSlot = recentChainData.getCurrentSlot();
              if (currentSlot.isEmpty()
                  || (!header.getSlot().equals(currentSlot.get())
                      && !header.getSlot().equals(currentSlot.get().increment()))) {
                return InternalValidationResult.IGNORE;
              }
              /*
               * [REJECT] The builder signature, signed_execution_payload_header_envelope.signature, is valid with respect to the header_envelope.builder_index.
               */
              if (!verifyBuilderSignature(builder.getPublicKey(), signedHeader, state)) {
                return reject(
                    "The builder signature is not valid for a builder with public key "
                        + builder.getPublicKey());
              }

              // cache the valid header and the bid value
              receivedValidExecutionPayloadHeaderInfoSet.add(slotAndBuilderIndex);
              highestBidValue.put(slotAndParentBlockHash, header.getValue());

              return InternalValidationResult.ACCEPT;
            });
  }

  private boolean verifyBuilderSignature(
      final BLSPublicKey publicKey,
      final SignedExecutionPayloadHeader signedHeader,
      final BeaconState state) {
    final Bytes32 domain =
        spec.getDomain(
            Domain.BEACON_BUILDER,
            spec.getCurrentEpoch(state),
            state.getFork(),
            state.getGenesisValidatorsRoot());
    final ExecutionPayloadHeaderEip7732 header =
        ExecutionPayloadHeaderEip7732.required(signedHeader.getMessage());
    final Bytes signingRoot = spec.computeSigningRoot(header, domain);
    return BLS.verify(publicKey, signingRoot, signedHeader.getSignature());
  }

  record SlotAndBuilderIndex(UInt64 slot, UInt64 builderIndex) {}

  record SlotAndParentBlockHash(UInt64 slot, Bytes32 parentBlockHash) {}
}