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

package tech.pegasys.teku.infrastructure.statediff;

import java.util.Arrays;
import java.util.List;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Epoch periods of the diff hierarchy, coarsest first. Level 0 has the longest period; the last
 * level has period 1 so every epoch belongs to some level. An epoch's level is the coarsest level
 * whose period divides it.
 */
public final class DiffLevels {

  private final long[] periods;

  private DiffLevels(final long[] periods) {
    this.periods = periods;
  }

  public static DiffLevels of(final List<Long> periodsDescending) {
    if (periodsDescending.isEmpty()) {
      throw new IllegalArgumentException("At least one diff level period is required");
    }
    final long[] periods = periodsDescending.stream().mapToLong(Long::longValue).toArray();
    for (int i = 0; i < periods.length; i++) {
      if (periods[i] < 1) {
        throw new IllegalArgumentException(
            "Diff level periods must be positive: " + periodsDescending);
      }
      if (i > 0 && (periods[i] >= periods[i - 1] || periods[i - 1] % periods[i] != 0)) {
        throw new IllegalArgumentException(
            "Diff level periods must be strictly descending and each divide the previous: "
                + periodsDescending);
      }
    }
    if (periods[periods.length - 1] != 1) {
      throw new IllegalArgumentException(
          "The finest diff level period must be 1: " + periodsDescending);
    }
    return new DiffLevels(periods);
  }

  public int levelCount() {
    return periods.length;
  }

  public long period(final int level) {
    return periods[level];
  }

  public int levelOf(final UInt64 epoch) {
    for (int level = 0; level < periods.length; level++) {
      if (epoch.mod(periods[level]).isZero()) {
        return level;
      }
    }
    throw new IllegalStateException("Unreachable: the finest period is 1");
  }

  /** Returns the coarsest level of any epoch in the inclusive range {@code [first, last]}. */
  public int maxLevelInRange(final UInt64 firstEpoch, final UInt64 lastEpoch) {
    if (firstEpoch.isGreaterThan(lastEpoch)) {
      throw new IllegalArgumentException(
          "Empty epoch range: " + firstEpoch + " is after " + lastEpoch);
    }
    if (firstEpoch.isZero()) {
      return 0;
    }
    final UInt64 beforeFirst = firstEpoch.decrement();
    for (int level = 0; level < periods.length; level++) {
      final long period = periods[level];
      if (lastEpoch.dividedBy(period).isGreaterThan(beforeFirst.dividedBy(period))) {
        return level;
      }
    }
    throw new IllegalStateException("Unreachable: the finest period is 1");
  }

  public boolean isCoarsest(final int level) {
    return level == 0;
  }

  @Override
  public String toString() {
    return "DiffLevels" + Arrays.toString(periods);
  }
}
