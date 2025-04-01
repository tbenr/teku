package tech.pegasys.teku.statetransition.attestation.utils;

import it.unimi.dsi.fastutil.ints.IntList;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.BeaconStateAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.BeaconStateAccessorsAltair;
import tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair;
import tech.pegasys.teku.storage.client.RecentChainData;

import java.util.Comparator;
import java.util.List;

import static tech.pegasys.teku.spec.logic.versions.altair.helpers.MiscHelpersAltair.PARTICIPATION_FLAG_WEIGHTS;

public class RewardBasedAttestationComparator {

    private final Spec spec;
    private final BeaconStateAltair state;
    private final BeaconStateAccessorsAltair beaconStateAccessors;
    private final MiscHelpersAltair miscHelpers;

    public static RewardBasedAttestationComparator create(final Spec spec, final BeaconState state) {
        final SpecVersion specVersion = spec.atSlot(state.getSlot());

        return new RewardBasedAttestationComparator(spec, BeaconStateAltair.required(state), BeaconStateAccessorsAltair.required(specVersion.beaconStateAccessors()), specVersion.miscHelpers().toVersionAltair().orElseThrow());
    }

    private RewardBasedAttestationComparator(final Spec spec, final BeaconStateAltair state, final BeaconStateAccessorsAltair beaconStateAccessors,
                                                final MiscHelpersAltair miscHelpers) {
        this.spec = spec;
        this.state = state;
        this.beaconStateAccessors = beaconStateAccessors;
        this.miscHelpers = miscHelpers;
    }

    private UInt64 getRewardNumeratorForAttestation(final BeaconStateAltair state, Attestation attestation) {
        final AttestationData data = attestation.getData();
        final List<Integer> participationFlagIndices = BeaconStateAccessorsAltair.required(
                beaconStateAccessors).getAttestationParticipationFlagIndices(state, data,
                state.getSlot().minusMinZero(data.getSlot()));

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
                final UInt64 weight = PARTICIPATION_FLAG_WEIGHTS.get(flagIndex);
                if (participationFlagIndices.contains(flagIndex) && miscHelpers.hasFlag(epochParticipation.get(attestingIndex).get(), flagIndex)) {

                    final UInt64 reward = BeaconStateAccessorsAltair.required(beaconStateAccessors)
                            .getBaseReward(state, attestingIndex);
                    proposerRewardNumerator = proposerRewardNumerator.plus(reward.times(weight));
                }
            }
        }

        return proposerRewardNumerator;
    }

    public Comparator<ValidatableAttestation> comparator() {
        return (a1, a2) -> getRewardNumeratorForAttestation(state, a1.getAttestation()).compareTo(
                getRewardNumeratorForAttestation(state, a2.getAttestation()));
    }
}