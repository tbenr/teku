/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.statetransition.attestation.utils;

import static com.google.common.base.Preconditions.checkState;
import static tech.pegasys.teku.spec.constants.IncentivizationWeights.TIMELY_HEAD_WEIGHT;
import static tech.pegasys.teku.spec.constants.IncentivizationWeights.TIMELY_SOURCE_WEIGHT;
import static tech.pegasys.teku.spec.constants.IncentivizationWeights.TIMELY_TARGET_WEIGHT;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszPrimitive;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.constants.IncentivizationWeights;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.BeaconStateAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.BeaconStateAccessorsAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair;

public class RewardBasedAttestationSorter {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final BeaconStateAltair state;
  private final BeaconStateAccessorsAltair beaconStateAccessors;
  private final MiscHelpersAltair miscHelpers;

  private List<Byte> currentEpochParticipation;
  private List<Byte> previousEpochParticipation;

  private static int TIMELY_SOURCE_INDEX =
      MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS.indexOf(TIMELY_SOURCE_WEIGHT);
  private static int TIMELY_TARGET_INDEX =
      MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS.indexOf(
          IncentivizationWeights.TIMELY_TARGET_WEIGHT);
  private static int TIMELY_HEAD_INDEX =
      MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS.indexOf(
          IncentivizationWeights.TIMELY_HEAD_WEIGHT);

  static {
    checkState(TIMELY_SOURCE_INDEX != -1);
    checkState(TIMELY_TARGET_INDEX != -1);
    checkState(TIMELY_HEAD_INDEX != -1);
    checkState(MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS.size() == 3);
  }

  public static RewardBasedAttestationSorter create(final Spec spec, final BeaconState state) {
    final SpecVersion specVersion = spec.atSlot(state.getSlot());

    return new RewardBasedAttestationSorter(
        spec,
        BeaconStateAltair.required(state),
        BeaconStateAccessorsAltair.required(specVersion.beaconStateAccessors()),
        specVersion.miscHelpers().toVersionAltair().orElseThrow());
  }

  private RewardBasedAttestationSorter(
      final Spec spec,
      final BeaconStateAltair state,
      final BeaconStateAccessorsAltair beaconStateAccessors,
      final MiscHelpersAltair miscHelpers) {
    this.spec = spec;
    this.state = state;
    this.beaconStateAccessors = beaconStateAccessors;
    this.miscHelpers = miscHelpers;
  }

  private List<Byte> getCurrentEpochParticipation() {
    if (currentEpochParticipation == null) {
      currentEpochParticipation =
          epochParticipationToMutableList(state.getCurrentEpochParticipation());
    }
    return currentEpochParticipation;
  }

  private List<Byte> getPreviousEpochParticipation() {
    if (previousEpochParticipation == null) {
      previousEpochParticipation =
          epochParticipationToMutableList(state.getPreviousEpochParticipation());
    }
    return previousEpochParticipation;
  }

  private List<Byte> getEpochParticipation(final AttestationWithRewardInfo attestation) {
    return attestation.isCurrentEpoch
        ? getCurrentEpochParticipation()
        : getPreviousEpochParticipation();
  }

  public List<AttestationWithRewardInfo> sort(
      final List<ValidatableAttestation> attestations, final int maxAttestations) {

    var start = System.nanoTime();
    final List<AttestationWithRewardInfo> finalSortedAttestations =
        new ArrayList<>(maxAttestations);

    final PriorityQueue<AttestationWithRewardInfo> attestationQueue =
        new PriorityQueue<>(REWARD_COMPARATOR);

    attestations.stream()
        .map(this::initializeRewardInfo)
        .peek(this::computeRewards)
        .forEach(attestationQueue::add);

    if (attestationQueue.isEmpty()) {
      return finalSortedAttestations;
    }

    var initializationEnded = System.nanoTime();
    LOG.info("Initialization took {} ms.", (initializationEnded - start) / 1_000_000);

    while (true) {
      final AttestationWithRewardInfo bestAttestation = attestationQueue.poll();
      finalSortedAttestations.add(bestAttestation);

      // we reached the limit or there are no more attestations to process
      if (finalSortedAttestations.size() >= maxAttestations || attestationQueue.isEmpty()) {
        LOG.info("Sorting took: {} ms", (System.nanoTime() - initializationEnded) / 1_000_000);
        return finalSortedAttestations;
      }

      // apply participation changes
      var affectedParticipation = getEpochParticipation(bestAttestation);
      if (bestAttestation.updatesEpochParticipation.isEmpty()) {
        // no changes to participation
        continue;
      }

      bestAttestation.updatesEpochParticipation.forEach(affectedParticipation::set);

      final List<AttestationWithRewardInfo> toReAdd = new ArrayList<>();

      // recalculate rewards for affected attestations
      for (final AttestationWithRewardInfo potentiallyAffected : attestationQueue) {
        if (potentiallyAffected.isCurrentEpoch == bestAttestation.isCurrentEpoch) {
          computeRewards(potentiallyAffected);
          toReAdd.add(potentiallyAffected);
        }
      }

      if (!toReAdd.isEmpty()) {
        // make sure PriorityQueue reevaluates the order
        attestationQueue.removeAll(toReAdd);
        attestationQueue.addAll(toReAdd);
      }
    }
  }

  private static List<Byte> epochParticipationToMutableList(
      final SszList<SszByte> epochParticipation) {
    return epochParticipation.stream()
        .map(AbstractSszPrimitive::get)
        .collect(Collectors.toCollection(ByteArrayList::new));
  }

  private final Int2ObjectOpenHashMap<UInt64> validatorBaseRewardCache =
      new Int2ObjectOpenHashMap<>();

  private UInt64 getValidatorBaseRewards(final int index) {
    return validatorBaseRewardCache.computeIfAbsent(
        index,
        k -> BeaconStateAccessorsAltair.required(beaconStateAccessors).getBaseReward(state, k));
  }

  private AttestationWithRewardInfo initializeRewardInfo(final ValidatableAttestation attestation) {
    final boolean isCurrentEpoch =
        attestation.getData().getTarget().getEpoch().equals(spec.getCurrentEpoch(state));
    final IntList attestingIndices = spec.getAttestingIndices(state, attestation.getAttestation());

    final List<Integer> attestationParticipationFlagIndices =
        beaconStateAccessors.getAttestationParticipationFlagIndices(
            state,
            attestation.getData(),
            state.getSlot().minusMinZero(attestation.getData().getSlot()));

    return new AttestationWithRewardInfo(
        attestation,
        attestingIndices,
        attestationParticipationFlagIndices.contains(TIMELY_SOURCE_INDEX),
        attestationParticipationFlagIndices.contains(TIMELY_TARGET_INDEX),
        attestationParticipationFlagIndices.contains(TIMELY_HEAD_INDEX),
        Map.of(),
        isCurrentEpoch,
        UInt64.ZERO);
  }

  private void computeRewards(final AttestationWithRewardInfo attestation) {
    final List<Byte> epochParticipation = getEpochParticipation(attestation);
    final Int2ByteOpenHashMap updatesEpochParticipation = new Int2ByteOpenHashMap();

    UInt64 proposerRewardNumerator = UInt64.ZERO;

    for (int i = 0; i < attestation.attestingIndices.size(); i++) {
      final int attestingIndex = attestation.attestingIndices.getInt(i);
      final byte previousParticipationFlags = epochParticipation.get(attestingIndex);
      byte newParticipationFlags = 0;

      final UInt64 baseReward = getValidatorBaseRewards(attestingIndex);

      if (attestation.timelySource
          && !miscHelpers.hasFlag(previousParticipationFlags, TIMELY_SOURCE_INDEX)) {
        newParticipationFlags = miscHelpers.addFlag(newParticipationFlags, TIMELY_SOURCE_INDEX);
        proposerRewardNumerator =
            proposerRewardNumerator.plus(baseReward.times(TIMELY_SOURCE_WEIGHT));
      }

      if (attestation.timelySource
          && !miscHelpers.hasFlag(previousParticipationFlags, TIMELY_TARGET_INDEX)) {
        newParticipationFlags = miscHelpers.addFlag(newParticipationFlags, TIMELY_TARGET_INDEX);
        proposerRewardNumerator =
            proposerRewardNumerator.plus(baseReward.times(TIMELY_TARGET_WEIGHT));
      }

      if (attestation.timelySource
          && !miscHelpers.hasFlag(previousParticipationFlags, TIMELY_HEAD_INDEX)) {
        newParticipationFlags = miscHelpers.addFlag(newParticipationFlags, TIMELY_HEAD_INDEX);
        proposerRewardNumerator =
            proposerRewardNumerator.plus(baseReward.times(TIMELY_HEAD_WEIGHT));
      }

      if (newParticipationFlags != 0) {
        updatesEpochParticipation.put(
            attestingIndex,
            miscHelpers.addFlags(previousParticipationFlags, newParticipationFlags));
      }
    }

    attestation.rewardNumerator = proposerRewardNumerator;
    attestation.updatesEpochParticipation = updatesEpochParticipation;
  }

  public static class AttestationWithRewardInfo {
    private final ValidatableAttestation attestation;
    private final IntList attestingIndices;
    private final boolean timelySource;
    private final boolean timelyTarget;
    private final boolean timelyHead;
    private final boolean isCurrentEpoch;

    private Map<Integer, Byte> updatesEpochParticipation;
    private UInt64 rewardNumerator;

    public AttestationWithRewardInfo withAttestation(final ValidatableAttestation attestation) {
      return new AttestationWithRewardInfo(
          attestation,
          this.attestingIndices,
          this.timelySource,
          this.timelyTarget,
          this.timelyHead,
          this.updatesEpochParticipation,
          this.isCurrentEpoch,
          this.rewardNumerator);
    }

    private AttestationWithRewardInfo(
        final ValidatableAttestation attestation,
        final IntList attestingIndices,
        final boolean timelySource,
        final boolean timelyTarget,
        final boolean timelyHead,
        final Map<Integer, Byte> updatesEpochParticipation,
        final boolean isCurrentEpoch,
        final UInt64 rewardNumerator) {
      this.attestation = attestation;
      this.attestingIndices = attestingIndices;
      this.timelySource = timelySource;
      this.timelyTarget = timelyTarget;
      this.timelyHead = timelyHead;
      this.updatesEpochParticipation = updatesEpochParticipation;
      this.isCurrentEpoch = isCurrentEpoch;
      this.rewardNumerator = rewardNumerator;
    }

    public ValidatableAttestation getAttestation() {
      return attestation;
    }

    public UInt64 getRewardNumerator() {
      return rewardNumerator;
    }
  }

  static final Comparator<AttestationWithRewardInfo> REWARD_COMPARATOR =
      Comparator.<AttestationWithRewardInfo>comparingLong(
              value -> value.rewardNumerator.longValue())
          .reversed();
}
