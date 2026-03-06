/*
 * Copyright Consensys Software Inc., 2026
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Manages the 7-level hierarchical diff configuration. Each level has a period in epochs and an
 * associated diff schema.
 *
 * <p>Level assignment: epoch E gets the lowest (most infrequent) level L where E % period(L) == 0.
 *
 * <p>Reconstruction: load level-0 snapshot, then apply diffs from each level in order.
 */
public class DiffHierarchy {

  private final List<Level> levels;
  private final Set<UInt64> forkEpochs;

  public DiffHierarchy(final List<Level> levels, final Set<UInt64> forkEpochs) {
    this.levels = List.copyOf(levels);
    this.forkEpochs = Set.copyOf(forkEpochs);
  }

  public record Level(long periodInEpochs, StateDiffSchema schema) {}

  public record LevelAndEpoch(int level, UInt64 epoch) {}

  /** Returns the number of hierarchy levels. */
  public int getLevelCount() {
    return levels.size();
  }

  /** Returns the schema for a given level. */
  public StateDiffSchema getSchema(final int level) {
    return levels.get(level).schema();
  }

  /** Returns the period for a given level. */
  public long getPeriod(final int level) {
    return levels.get(level).periodInEpochs();
  }

  /**
   * Given an epoch, determine which levels to write. Returns a list of level indices, with the
   * lowest-indexed (most infrequent) level first. Fork epochs always get a level-0 snapshot.
   */
  public List<Integer> getLevelsToWrite(final UInt64 epoch) {
    // Fork epochs always force a full snapshot
    if (forkEpochs.contains(epoch)) {
      return List.of(0);
    }

    final long epochVal = epoch.longValue();

    // Find the lowest level (most infrequent) where epoch is aligned
    for (int i = 0; i < levels.size(); i++) {
      if (epochVal % levels.get(i).periodInEpochs() == 0) {
        return List.of(i);
      }
    }

    // Should always match the last level (period=1)
    return List.of(levels.size() - 1);
  }

  /**
   * Given an epoch and level, determine the parent epoch (the epoch of the base state for diff
   * computation). For level 0, there is no parent. For other levels, the parent is at the same
   * level, one period earlier.
   */
  public UInt64 getParentEpoch(final UInt64 epoch, final int level) {
    if (level == 0) {
      throw new IllegalArgumentException("Level 0 (snapshot) has no parent epoch");
    }
    final long period = levels.get(level).periodInEpochs();
    return epoch.minus(period);
  }

  /**
   * Given a target epoch, compute the reconstruction chain: a list of (level, epoch) pairs starting
   * from the nearest level-0 snapshot, through each intermediate level, ending at the target epoch.
   */
  public List<LevelAndEpoch> getReconstructionChain(final UInt64 targetEpoch) {
    final List<LevelAndEpoch> chain = new ArrayList<>();
    final long targetVal = targetEpoch.longValue();

    // Find nearest level-0 epoch at or before target
    final long level0Period = levels.get(0).periodInEpochs();
    long currentEpoch = targetVal - (targetVal % level0Period);

    // Check for fork epochs between currentEpoch and targetEpoch that might be closer snapshots
    for (final UInt64 forkEpoch : forkEpochs) {
      final long forkVal = forkEpoch.longValue();
      if (forkVal > currentEpoch && forkVal <= targetVal) {
        currentEpoch = forkVal;
      }
    }

    chain.add(new LevelAndEpoch(0, UInt64.valueOf(currentEpoch)));

    if (currentEpoch == targetVal) {
      return chain;
    }

    // For each subsequent level (1..N), find the latest aligned epoch after currentEpoch
    // that doesn't exceed target
    for (int level = 1; level < levels.size(); level++) {
      final long period = levels.get(level).periodInEpochs();

      // Find the last aligned epoch at this level that's <= target and > currentEpoch
      final long latestAligned = targetVal - (targetVal % period);
      if (latestAligned <= currentEpoch) {
        // No aligned epoch at this level between current and target
        continue;
      }

      chain.add(new LevelAndEpoch(level, UInt64.valueOf(latestAligned)));
      currentEpoch = latestAligned;

      if (currentEpoch == targetVal) {
        return chain;
      }
    }

    return chain;
  }

  /**
   * Creates the default 7-level hierarchy.
   *
   * @param fieldLocator locator for UInt64 fields in SSZ
   * @param forkEpochs set of fork transition epochs that require snapshots
   */
  public static DiffHierarchy createDefault(
      final SszFieldLocator fieldLocator, final Set<UInt64> forkEpochs) {

    final SnapshotDiffSchema snapshotSchema = new SnapshotDiffSchema();
    final SimpleSszDiff.Schema simpleSchema = new SimpleSszDiff.Schema();
    final CompositeDiffSchema compositeSchema = new CompositeDiffSchema(fieldLocator);

    final List<Level> levels =
        List.of(
            new Level(65536, snapshotSchema), // ~291 days
            new Level(8192, new CompressedDiffSchema(simpleSchema)), // ~36 days
            new Level(2048, new CompressedDiffSchema(simpleSchema)), // ~9 days
            new Level(256, new CompressedDiffSchema(simpleSchema)), // ~27 hours
            new Level(64, new CompressedDiffSchema(compositeSchema)), // ~6.8 hours
            new Level(16, new CompressedDiffSchema(compositeSchema)), // ~1.7 hours
            new Level(1, new CompressedDiffSchema(compositeSchema))); // every epoch

    return new DiffHierarchy(levels, forkEpochs);
  }
}
