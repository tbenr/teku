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

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;
import tech.pegasys.teku.infrastructure.statediff.DiffLevels;

/**
 * Configuration of the state diff DAG.
 *
 * @param enabled whether hot states are stored as DAG nodes
 * @param levels epoch periods of the diff levels, coarsest first, each dividing the previous and
 *     ending with 1
 * @param retainedSlots on an archive database, the slots of finalized history kept in the DAG
 *     before the finalized checkpoint (the retention window); 0 keeps all of it. Applies whenever
 *     the database uses state diff storage, also when it was enabled by the database's marker
 *     rather than by this configuration
 */
public record StateDiffDagConfig(boolean enabled, List<Long> levels, long retainedSlots) {

  private static final List<Long> PRUNE_LEVELS = List.of(256L, 64L, 16L, 1L);

  public StateDiffDagConfig(
      final boolean enabled, final List<Long> levels, final long retainedSlots) {
    checkArgument(retainedSlots >= 0, "Retained slots must not be negative: %s", retainedSlots);
    this.enabled = enabled;
    this.levels = List.copyOf(levels);
    this.retainedSlots = retainedSlots;
    // Validates the period list
    DiffLevels.of(this.levels);
  }

  /** Unbounded finalized history. */
  public StateDiffDagConfig(final boolean enabled, final List<Long> levels) {
    this(enabled, levels, 0);
  }

  public static StateDiffDagConfig disabled() {
    return new StateDiffDagConfig(false, PRUNE_LEVELS);
  }

  /** Prune mode defaults: levels {@code [256, 64, 16, 1]}. */
  public static StateDiffDagConfig prune() {
    return new StateDiffDagConfig(true, PRUNE_LEVELS);
  }

  public StateDiffDagConfig withRetainedSlots(final long retainedSlots) {
    return new StateDiffDagConfig(enabled, levels, retainedSlots);
  }

  public DiffLevels diffLevels() {
    return DiffLevels.of(levels);
  }
}
