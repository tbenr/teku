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

package tech.pegasys.teku.statetransition.attestation.v2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.spec.SpecMilestone.ELECTRA;
import static tech.pegasys.teku.spec.SpecMilestone.PHASE0;

import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPool;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPoolTest;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter.AttestationWithRewardInfo;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter.RewardBasedAttestationSorterFactory;
import tech.pegasys.teku.storage.client.RecentChainData;

import java.util.List;

@TestSpecContext(milestone = {ELECTRA})
public class AggregatingAttestationPoolV2Test extends AggregatingAttestationPoolTest {

  @Override
  @SuppressWarnings("unchecked")
  public AggregatingAttestationPool instantiatePool(
      final Spec spec, final RecentChainData recentChainData, final int maxAttestations) {
    final RewardBasedAttestationSorterFactory sorterFactory = mock(RewardBasedAttestationSorterFactory.class);
    final RewardBasedAttestationSorter sorter = mock(RewardBasedAttestationSorter.class);

    // Mock the sorter to return the input list as sorted

    doAnswer(invocationOnMock ->
            ((List<ValidatableAttestation>)invocationOnMock.getArgument(0)).stream().map(att -> {
                      var ret = mock(AttestationWithRewardInfo.class);
                        when(ret.getAttestation()).thenReturn(att);
                        when(ret.getRewardNumerator()).thenReturn(UInt64.ZERO);
                        return ret;
                    }
            ).limit(invocationOnMock.getArgument(1))
                    .toList()
    ).when(sorter).sort(anyList(), anyInt());
    when(sorterFactory.create(any())).thenReturn(sorter);

    return new AggregatingAttestationPoolV2(
        spec, recentChainData, new NoOpMetricsSystem(), maxAttestations, System::nanoTime, sorterFactory);
  }
}
