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

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.BeaconStateAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.BeaconStateAccessorsAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair;

public class AttestationRewardCalculator {

  private final Spec spec;
  private final BeaconStateAltair state;
  private final BeaconStateAccessorsAltair beaconStateAccessors;
  private final MiscHelpersAltair miscHelpers;

  public static AttestationRewardCalculator create(final Spec spec, final BeaconState state) {
    final SpecVersion specVersion = spec.atSlot(state.getSlot());

    return new AttestationRewardCalculator(
        spec,
        BeaconStateAltair.required(state),
        BeaconStateAccessorsAltair.required(specVersion.beaconStateAccessors()),
        specVersion.miscHelpers().toVersionAltair().orElseThrow());
  }

  private AttestationRewardCalculator(
      final Spec spec,
      final BeaconStateAltair state,
      final BeaconStateAccessorsAltair beaconStateAccessors,
      final MiscHelpersAltair miscHelpers) {
    this.spec = spec;
    this.state = state;
    this.beaconStateAccessors = beaconStateAccessors;
    this.miscHelpers = miscHelpers;
  }

  public long getRewardNumeratorForAttestation(final Attestation attestation) {
    final AttestationData data = attestation.getData();
    final List<Integer> participationFlagIndices =
        BeaconStateAccessorsAltair.required(beaconStateAccessors)
            .getAttestationParticipationFlagIndices(
                state, data, state.getSlot().minusMinZero(data.getSlot()));

    final SszList<SszByte> epochParticipation;
    if (data.getTarget().getEpoch().equals(spec.getCurrentEpoch(state))) {
      epochParticipation = state.getCurrentEpochParticipation();
    } else {
      epochParticipation = state.getPreviousEpochParticipation();
    }

    UInt64 proposerRewardNumerator = UInt64.ZERO;
    final IntList attestingIndices = spec.getAttestingIndices(state, attestation);
    for (final Integer attestingIndex : attestingIndices) {
      for (int flagIndex = 0; flagIndex < PARTICIPATION_FLAG_WEIGHTS.size(); flagIndex++) {
        if (participationFlagIndices.contains(flagIndex)
            && !miscHelpers.hasFlag(epochParticipation.get(attestingIndex).get(), flagIndex)) {

          final UInt64 weight = PARTICIPATION_FLAG_WEIGHTS.get(flagIndex);

          final UInt64 reward =
              BeaconStateAccessorsAltair.required(beaconStateAccessors)
                  .getBaseReward(state, attestingIndex);

          proposerRewardNumerator = proposerRewardNumerator.plus(reward.times(weight));
        }
      }
    }

    return proposerRewardNumerator.longValue();
  }
}
