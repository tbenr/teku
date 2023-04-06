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

import java.time.Duration;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

@FunctionalInterface
interface BlockBlobsSidecarsTrackerTimingStrategy {
  TrackerTimings calculateTimings(final UInt64 slot);

  class TrackerTimings {
    final UInt64 firstSeenMillis;
    final Optional<Duration> fetchDelay;
    final Optional<Duration> giveUpDelay;

    public TrackerTimings(
        final UInt64 firstSeenMillis,
        final Optional<Duration> fetchDelay,
        final Optional<Duration> giveUpDelay) {
      this.firstSeenMillis = firstSeenMillis;
      this.fetchDelay = fetchDelay;
      this.giveUpDelay = giveUpDelay;
    }
  }
}
