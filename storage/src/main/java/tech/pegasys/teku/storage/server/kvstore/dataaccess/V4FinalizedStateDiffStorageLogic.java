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

package tech.pegasys.teku.storage.server.kvstore.dataaccess;

import com.google.errorprone.annotations.MustBeClosed;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.logging.LoggingConfigurator;
import tech.pegasys.teku.infrastructure.statediff.DiffHierarchy;
import tech.pegasys.teku.infrastructure.statediff.StateDiff;
import tech.pegasys.teku.infrastructure.statediff.StateDiffSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.ColumnEntry;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor.KvStoreTransaction;
import tech.pegasys.teku.storage.server.kvstore.schema.SchemaCombinedDiffState;

/**
 * Finalized state storage using hierarchical SSZ diffs. Stores state diffs at 7 hierarchy levels,
 * reconstructing states by loading a snapshot and applying a chain of diffs.
 */
public class V4FinalizedStateDiffStorageLogic
    implements V4FinalizedStateStorageLogic<SchemaCombinedDiffState> {

  private static final Logger LOG = LogManager.getLogger();

  private final DiffHierarchy hierarchy;
  private final Spec spec;

  static {
    LoggingConfigurator.setAllLevels("tech.pegasys.teku.storage.server.kvstore", Level.DEBUG);
  }

  public V4FinalizedStateDiffStorageLogic(final DiffHierarchy hierarchy, final Spec spec) {
    this.hierarchy = hierarchy;
    this.spec = spec;
  }

  public static V4FinalizedStateDiffStorageLogic create(final Spec spec) {
    final BeaconStateSszFieldLocator fieldLocator = BeaconStateSszFieldLocator.create(spec);
    final DiffHierarchy hierarchy =
        DiffHierarchy.createDefault(fieldLocator, BeaconStateSszFieldLocator.getForkEpochs(spec));
    return new V4FinalizedStateDiffStorageLogic(hierarchy, spec);
  }

  @Override
  public Optional<BeaconState> getLatestAvailableFinalizedState(
      final KvStoreAccessor db, final SchemaCombinedDiffState schema, final UInt64 maxSlot) {
    final UInt64 maxEpoch = spec.computeEpochAtSlot(maxSlot);
    return findLatestStoredEpoch(db, schema, maxEpoch)
        .flatMap(targetEpoch -> reconstructState(db, schema, targetEpoch));
  }

  @Override
  public Optional<UInt64> getEarliestAvailableFinalizedStateSlot(
      final KvStoreAccessor db, final SchemaCombinedDiffState schema) {
    // Check level 0 (snapshots) for the earliest entry
    return db.getFirstEntry(schema.getColumnStateDiffLevel0())
        .map(entry -> spec.computeStartSlotAtEpoch(entry.getKey()));
  }

  @Override
  public FinalizedStateUpdater<SchemaCombinedDiffState> updater() {
    return new DiffStateUpdater(hierarchy, spec);
  }

  @Override
  public Optional<BeaconState> getLatestFinalizedState(
      final KvStoreAccessor db, final SchemaCombinedDiffState schema) {
    final Optional<UInt64> latestEpoch = findLatestStoredEpoch(db, schema, UInt64.MAX_VALUE);
    LOG.debug("getLatestFinalizedState: latestStoredEpoch={}", latestEpoch);
    return latestEpoch.flatMap(targetEpoch -> reconstructState(db, schema, targetEpoch));
  }

  @Override
  public boolean canReconstructLatestFinalizedState() {
    return true;
  }

  @Override
  @MustBeClosed
  public Stream<UInt64> streamFinalizedStateSlots(
      final KvStoreAccessor db,
      final SchemaCombinedDiffState schema,
      final UInt64 startSlot,
      final UInt64 endSlot) {
    // Find the latest level-0 snapshot — everything at or after it must be preserved
    final Optional<UInt64> latestSnapshotEpoch = db.getLastKey(schema.getColumnStateDiffLevel0());
    if (latestSnapshotEpoch.isEmpty()) {
      return Stream.empty();
    }

    final UInt64 startEpoch = spec.computeEpochAtSlot(startSlot);
    final UInt64 requestedEndEpoch = spec.computeEpochAtSlot(endSlot);

    // Cap end before the latest snapshot (preserve it and everything after)
    final UInt64 safeEndEpoch = latestSnapshotEpoch.get().minusMinZero(1);
    final UInt64 endEpoch = requestedEndEpoch.min(safeEndEpoch);

    if (startEpoch.isGreaterThan(endEpoch)) {
      return Stream.empty();
    }

    // Stream entries from ALL levels in the prunable range
    final TreeSet<UInt64> prunableEpochs = new TreeSet<>();
    for (int level = 0; level < hierarchy.getLevelCount(); level++) {
      try (Stream<UInt64> keys =
          db.streamKeys(schema.getColumnStateDiffLevel(level), startEpoch, endEpoch)) {
        keys.forEach(prunableEpochs::add);
      }
    }

    return prunableEpochs.stream().map(epoch -> spec.computeStartSlotAtEpoch(epoch));
  }

  private Optional<UInt64> findLatestStoredEpoch(
      final KvStoreAccessor db, final SchemaCombinedDiffState schema, final UInt64 maxEpoch) {
    for (int level = hierarchy.getLevelCount() - 1; level >= 0; level--) {
      final Optional<ColumnEntry<UInt64, Bytes>> entry =
          db.getFloorEntry(schema.getColumnStateDiffLevel(level), maxEpoch);
      if (entry.isPresent()) {
        return Optional.of(entry.get().getKey());
      }
    }
    return Optional.empty();
  }

  private Optional<BeaconState> reconstructState(
      final KvStoreAccessor db, final SchemaCombinedDiffState schema, final UInt64 targetEpoch) {
    return reconstructSszBytes(db, schema, hierarchy, targetEpoch)
        .map(spec::deserializeBeaconState);
  }

  private static Optional<Bytes> reconstructSszBytes(
      final KvStoreAccessor db,
      final SchemaCombinedDiffState schema,
      final DiffHierarchy hierarchy,
      final UInt64 targetEpoch) {
    final List<DiffHierarchy.LevelAndEpoch> chain = hierarchy.getReconstructionChain(targetEpoch);
    if (chain.isEmpty()) {
      LOG.debug("reconstructSszBytes: empty chain for epoch {}", targetEpoch);
      return Optional.empty();
    }
    LOG.debug(
        "reconstructSszBytes: epoch={}, chain length={}, levels={}",
        targetEpoch,
        chain.size(),
        chain);

    final DiffHierarchy.LevelAndEpoch snapshotEntry = chain.get(0);
    final Optional<Bytes> snapshotBytes =
        db.get(schema.getColumnStateDiffLevel(snapshotEntry.level()), snapshotEntry.epoch());
    if (snapshotBytes.isEmpty()) {
      LOG.warn("Missing snapshot at epoch {} for reconstruction", snapshotEntry.epoch());
      return Optional.empty();
    }

    Bytes currentSsz =
        hierarchy
            .getSchema(snapshotEntry.level())
            .deserialize(snapshotBytes.get())
            .apply(Bytes.EMPTY);

    for (int i = 1; i < chain.size(); i++) {
      final DiffHierarchy.LevelAndEpoch levelAndEpoch = chain.get(i);
      final Optional<Bytes> diffBytes =
          db.get(schema.getColumnStateDiffLevel(levelAndEpoch.level()), levelAndEpoch.epoch());
      if (diffBytes.isEmpty()) {
        LOG.warn(
            "Missing diff at level {} epoch {} for reconstruction",
            levelAndEpoch.level(),
            levelAndEpoch.epoch());
        return Optional.empty();
      }
      currentSsz =
          hierarchy.getSchema(levelAndEpoch.level()).deserialize(diffBytes.get()).apply(currentSsz);
    }

    LOG.debug(
        "reconstructSszBytes: epoch={}, result size={} bytes", targetEpoch, currentSsz.size());
    return Optional.of(currentSsz);
  }

  private static class DiffStateUpdater implements FinalizedStateUpdater<SchemaCombinedDiffState> {

    private final DiffHierarchy hierarchy;
    private final Spec spec;
    // Cache the last SSZ bytes to use as base for the next diff
    private Bytes lastSszBytes;
    private UInt64 lastEpoch;

    DiffStateUpdater(final DiffHierarchy hierarchy, final Spec spec) {
      this.hierarchy = hierarchy;
      this.spec = spec;
    }

    @Override
    public void addFinalizedState(
        final KvStoreAccessor db,
        final KvStoreTransaction transaction,
        final SchemaCombinedDiffState schema,
        final BeaconState state) {
      final UInt64 epoch = spec.computeEpochAtSlot(state.getSlot());
      final List<Integer> levels = hierarchy.getLevelsToWrite(epoch);
      final Bytes stateSsz = state.sszSerialize();
      LOG.debug(
          "addFinalizedState: slot={}, epoch={}, sszSize={} bytes, levels={}",
          state.getSlot(),
          epoch,
          stateSsz.size(),
          levels);

      for (final int level : levels) {
        final StateDiffSchema diffSchema = hierarchy.getSchema(level);

        if (level == 0) {
          // Snapshot - no base needed
          final StateDiff diff = diffSchema.computeDiff(Bytes.EMPTY, stateSsz);
          final Bytes serialized = diff.serialize();
          LOG.debug(
              "addFinalizedState: level=0 (snapshot), epoch={}, diffSize={} bytes",
              epoch,
              serialized.size());
          transaction.put(schema.getColumnStateDiffLevel(level), epoch, serialized);
        } else {
          // Need base state SSZ from parent epoch
          final UInt64 parentEpoch = hierarchy.getParentEpoch(epoch, level);
          final Bytes baseSsz = getOrReconstructSsz(db, schema, parentEpoch);
          if (baseSsz != null) {
            final StateDiff diff = diffSchema.computeDiff(baseSsz, stateSsz);
            final Bytes serialized = diff.serialize();
            LOG.debug(
                "addFinalizedState: level={}, epoch={}, parentEpoch={}, diffSize={} bytes",
                level,
                epoch,
                parentEpoch,
                serialized.size());
            transaction.put(schema.getColumnStateDiffLevel(level), epoch, serialized);
          } else {
            LOG.warn(
                "Cannot compute diff for level {} epoch {}: missing base at epoch {}",
                level,
                epoch,
                parentEpoch);
          }
        }
      }

      // Cache for subsequent diffs in this batch
      lastSszBytes = stateSsz;
      lastEpoch = epoch;
    }

    @Override
    public void addReconstructedFinalizedState(
        final KvStoreAccessor db,
        final KvStoreTransaction transaction,
        final SchemaCombinedDiffState schema,
        final BeaconState state) {
      // Treat reconstructed states the same as regular finalized states
      addFinalizedState(db, transaction, schema, state);
    }

    @Override
    public void deleteFinalizedState(
        final KvStoreTransaction transaction,
        final SchemaCombinedDiffState schema,
        final UInt64 slot) {
      final UInt64 epoch = spec.computeEpochAtSlot(slot);
      for (int level = 0; level < hierarchy.getLevelCount(); level++) {
        transaction.delete(schema.getColumnStateDiffLevel(level), epoch);
      }
    }

    @Override
    public void commit() {
      lastSszBytes = null;
      lastEpoch = null;
    }

    private Bytes getOrReconstructSsz(
        final KvStoreAccessor db, final SchemaCombinedDiffState schema, final UInt64 epoch) {
      if (lastEpoch != null && lastEpoch.equals(epoch)) {
        return lastSszBytes;
      }
      return reconstructSszBytes(db, schema, hierarchy, epoch).orElse(null);
    }
  }
}
