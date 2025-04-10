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

import static tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszPrimitive;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.BeaconStateAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.BeaconStateAccessorsAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair;

public class RewardBasedAttestationSorter {
  private final Spec spec;
  private final BeaconStateAltair state;
  private final BeaconStateAccessorsAltair beaconStateAccessors;
  private final MiscHelpersAltair miscHelpers;

  private List<Byte> currentEpochParticipation;
  private List<Byte> previousEpochParticipation;

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
      final Stream<ValidatableAttestation> attestations, final int maxAttestations) {

    final List<AttestationWithRewardInfo> finalSortedAttestations =
        new ArrayList<>(maxAttestations);

    var currentSorted =
        attestations
            .map(this::initializeRewardInfo)
            .map(this::computeRewards)
            .sorted(REWARD_COMPARATOR)
            .collect(Collectors.toCollection(ArrayList::new));

    if (currentSorted.isEmpty()) {
      return finalSortedAttestations;
    }

    while (true) {
      var bestRemainingAttestation = currentSorted.removeFirst();
      finalSortedAttestations.add(bestRemainingAttestation);

      // we reached the limit or there are no more attestations to process
      if (finalSortedAttestations.size() >= maxAttestations || currentSorted.isEmpty()) {
        return finalSortedAttestations;
      }

      // apply participation changes
      var affectedParticipation = getEpochParticipation(bestRemainingAttestation);
      if (bestRemainingAttestation.updatesEpochParticipation.isEmpty()) {
        // no changes to participation
        continue;
      }

      bestRemainingAttestation.updatesEpochParticipation.forEach(affectedParticipation::set);

      // recalculate rewards for affected attestations
      final boolean[] requireResorting = {false};
      currentSorted.stream()
          .filter(
              attestation -> attestation.isCurrentEpoch == bestRemainingAttestation.isCurrentEpoch)
          .forEach(
              attestation -> {
                computeRewards(attestation);
                requireResorting[0] = true;
              });

      if (requireResorting[0]) {
        currentSorted.sort(REWARD_COMPARATOR);
      }
    }
  }

  private static List<Byte> epochParticipationToMutableList(
      final SszList<SszByte> epochParticipation) {
    return epochParticipation.stream()
        .map(AbstractSszPrimitive::get)
        .collect(Collectors.toCollection(ByteArrayList::new));
  }

  private AttestationWithRewardInfo initializeRewardInfo(final ValidatableAttestation attestation) {
    final boolean isCurrentEpoch =
        attestation.getData().getTarget().getEpoch().equals(spec.getCurrentEpoch(state));
    final IntList attestingIndices = spec.getAttestingIndices(state, attestation.getAttestation());

    final Map<Integer, UInt64> validatorBaseRewards =
        new Int2ObjectOpenHashMap<>(attestingIndices.size());

    attestingIndices
        .intStream()
        .forEach(
            attestingIndex ->
                validatorBaseRewards.put(
                    attestingIndex,
                    BeaconStateAccessorsAltair.required(beaconStateAccessors)
                        .getBaseReward(state, attestingIndex)));

    return new AttestationWithRewardInfo(
        attestation,
        attestingIndices,
        beaconStateAccessors.getAttestationParticipationFlagIndices(
            state,
            attestation.getData(),
            state.getSlot().minusMinZero(attestation.getData().getSlot())),
        validatorBaseRewards,
        Map.of(),
        isCurrentEpoch,
        UInt64.ZERO);
  }

  private AttestationWithRewardInfo computeRewards(final AttestationWithRewardInfo attestation) {

    final List<Integer> participationFlagIndices = attestation.participationFlagIndices;

    final List<Byte> epochParticipation = getEpochParticipation(attestation);
    final Map<Integer, Byte> updatesEpochParticipation = new Int2ByteOpenHashMap();

    UInt64 proposerRewardNumerator = UInt64.ZERO;

    for (final Integer attestingIndex : attestation.attestingIndices) {
      final byte previousParticipationFlags = epochParticipation.get(attestingIndex);
      byte newParticipationFlags = 0;

      final UInt64 baseReward = attestation.validatorBaseRewards.get(attestingIndex);

      for (int flagIndex = 0; flagIndex < PARTICIPATION_FLAG_WEIGHTS.size(); flagIndex++) {

        if (participationFlagIndices.contains(flagIndex)
            && !miscHelpers.hasFlag(previousParticipationFlags, flagIndex)) {

          final UInt64 weight = PARTICIPATION_FLAG_WEIGHTS.get(flagIndex);

          newParticipationFlags = miscHelpers.addFlag(newParticipationFlags, flagIndex);
          proposerRewardNumerator = proposerRewardNumerator.plus(baseReward.times(weight));
        }
      }

      if (newParticipationFlags != 0) {
        updatesEpochParticipation.put(
            attestingIndex,
            miscHelpers.addFlags(previousParticipationFlags, newParticipationFlags));
      }
    }

    return new AttestationWithRewardInfo(
        attestation.attestation,
        attestation.attestingIndices,
        attestation.participationFlagIndices,
        attestation.validatorBaseRewards,
        updatesEpochParticipation,
        attestation.isCurrentEpoch,
        proposerRewardNumerator);
  }

  public record AttestationWithRewardInfo(
      ValidatableAttestation attestation,
      IntList attestingIndices,
      List<Integer> participationFlagIndices,
      Map<Integer, UInt64> validatorBaseRewards,
      Map<Integer, Byte> updatesEpochParticipation,
      boolean isCurrentEpoch,
      UInt64 rewardNumerator) {}

  static final Comparator<AttestationWithRewardInfo> REWARD_COMPARATOR =
      Comparator.<AttestationWithRewardInfo>comparingLong(
              value -> value.rewardNumerator.longValue())
          .reversed();
}
