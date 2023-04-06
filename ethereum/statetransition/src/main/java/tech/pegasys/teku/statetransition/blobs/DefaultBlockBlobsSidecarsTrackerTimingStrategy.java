/*
 * Copyright ConsenSys Software Inc., 2023
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

package tech.pegasys.teku.statetransition.blobs;

import static tech.pegasys.teku.infrastructure.time.TimeUtilities.secondsToMillis;

import java.time.Duration;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.storage.client.RecentChainData;

public class DefaultBlockBlobsSidecarsTrackerTimingStrategy
    implements BlockBlobsSidecarsTrackerTimingStrategy {
  private final Spec spec;
  private final TimeProvider timeProvider;
  private final RecentChainData recentChainData;

  public DefaultBlockBlobsSidecarsTrackerTimingStrategy(
      final Spec spec, final RecentChainData recentChainData, final TimeProvider timeProvider) {
    this.spec = spec;
    this.timeProvider = timeProvider;
    this.recentChainData = recentChainData;
  }

  @Override
  public TrackerTimings calculateTimings(final UInt64 slot) {
    final UInt64 nowMillis = timeProvider.getTimeInMillis();
    final UInt64 slotStartTimeMillis = secondsToMillis(recentChainData.computeTimeAtSlot(slot));
    final UInt64 millisPerSlot = secondsToMillis(spec.getSecondsPerSlot(slot));

    return new TrackerTimings(
        timeProvider.getTimeInMillis(),
        calculateFetchDelay(nowMillis, slotStartTimeMillis, millisPerSlot),
        calculateGiveUpDelay(nowMillis, slotStartTimeMillis, millisPerSlot));
  }

  private static Optional<Duration> calculateFetchDelay(
      final UInt64 nowMillis, final UInt64 slotStartTimeMillis, final UInt64 millisPerSlot) {
    if (nowMillis.isLessThan(slotStartTimeMillis)) {
      return Optional.empty();
    }

    return Optional.empty();
  }

  private static Optional<Duration> calculateGiveUpDelay(
      final UInt64 nowMillis, final UInt64 slotStartTimeMillis, final UInt64 millisPerSlot) {
    if (nowMillis.isLessThan(slotStartTimeMillis)) {
      return Optional.empty();
    }

    return Optional.empty();
  }
}
