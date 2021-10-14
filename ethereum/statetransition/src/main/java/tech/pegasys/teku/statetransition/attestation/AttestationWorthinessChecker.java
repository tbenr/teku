/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.attestation;

import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

public interface AttestationWorthinessChecker {
  AttestationWorthinessChecker NOOP = attestationGroup -> true;

  static AttestationWorthinessChecker createAttestationWorthinessChecker(
      final Spec spec, final BeaconState state) {
    return spec.atSlot(state.getSlot()).getMilestone().isGreaterThanOrEqualTo(SpecMilestone.ALTAIR)
        ? new AttestationWorthinessCheckerAltair(spec, state)
        : AttestationWorthinessChecker.NOOP;
  }

  boolean areAttestationsWorthy(final MatchingDataAttestationGroup attestationGroup);
}
