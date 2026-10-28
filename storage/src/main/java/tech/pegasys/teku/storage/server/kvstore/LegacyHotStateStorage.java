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

package tech.pegasys.teku.storage.server.kvstore;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.api.StorageUpdate;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationReport;

/**
 * Hot states and the finalized checkpoint block state stored whole in the legacy columns. When
 * state diff storage is requested, this database switches to it once it has a finalized state to
 * start the DAG from.
 */
class LegacyHotStateStorage implements HotStateStorage {
  private static final Logger LOG = LogManager.getLogger();

  private final KvStoreCombinedDao dao;
  private final Optional<StateDiffHotStateStorage> requestedStateDiffStorage;

  /** {@code requestedStateDiffStorage} is the state diff storage to switch to, when requested. */
  LegacyHotStateStorage(
      final KvStoreCombinedDao dao,
      final Optional<StateDiffHotStateStorage> requestedStateDiffStorage) {
    this.dao = dao;
    this.requestedStateDiffStorage = requestedStateDiffStorage;
  }

  @Override
  public boolean isStateDiffStorage() {
    return false;
  }

  @Override
  public boolean holdsFinalizedStates() {
    return false;
  }

  @Override
  public Optional<BeaconState> getLatestAvailableFinalizedState(final UInt64 maxSlot) {
    return dao.getLatestAvailableFinalizedState(maxSlot);
  }

  @Override
  public Optional<UInt64> getEarliestAvailableFinalizedStateSlot() {
    return dao.getEarliestFinalizedStateSlot();
  }

  @Override
  public Optional<BeaconState> getLatestFinalizedState() {
    return dao.getLatestFinalizedState();
  }

  @Override
  public Optional<BeaconState> loadLatestFinalizedState() {
    return dao.getLatestFinalizedState();
  }

  /**
   * {@inheritDoc}
   *
   * <p>When state diff storage is requested and the database has a legacy finalized state: write
   * the finalized anchor as a snapshot node, set the mode marker and delete the legacy state, all
   * in one transaction. A database without a finalized state switches when its initial anchor is
   * stored.
   */
  @Override
  public Optional<Activation> getPendingActivation() {
    if (requestedStateDiffStorage.isEmpty()) {
      return Optional.empty();
    }
    final Optional<BeaconState> latestFinalizedState = dao.getLatestFinalizedState();
    if (latestFinalizedState.isEmpty()) {
      return Optional.empty();
    }
    final Checkpoint finalizedCheckpoint = dao.getFinalizedCheckpoint().orElseThrow();
    final StateDiffHotStateStorage stateDiffStorage = requestedStateDiffStorage.get();
    return Optional.of(
        new Activation() {
          @Override
          public HotStateStorage write(final HotUpdater updater) {
            stateDiffStorage.writeAnchorSnapshot(
                updater, finalizedCheckpoint, latestFinalizedState.get());
            updater.deleteLatestFinalizedState();
            return stateDiffStorage;
          }

          @Override
          public void logActivated() {
            LOG.info(
                "Switched the database to state diff storage at finalized checkpoint {}",
                finalizedCheckpoint);
          }
        });
  }

  /**
   * {@inheritDoc}
   *
   * <p>A new database created with state diff storage requested writes its anchor as a snapshot
   * node and uses state diff storage from then on.
   */
  @Override
  public HotStateStorage storeInitialAnchor(
      final HotUpdater updater, final Checkpoint anchorCheckpoint, final BeaconState anchorState) {
    if (requestedStateDiffStorage.isPresent()) {
      return requestedStateDiffStorage
          .get()
          .storeInitialAnchor(updater, anchorCheckpoint, anchorState);
    }
    updater.setLatestFinalizedState(anchorState);
    return this;
  }

  @Override
  public HotStateUpdate startUpdate(final StorageUpdate update) {
    return new HotStateUpdate() {
      @Override
      public void writeLatestFinalizedState(final HotUpdater updater) {
        update.getLatestFinalizedState().ifPresent(updater::setLatestFinalizedState);
      }

      @Override
      public void writeHotStates(final HotUpdater updater) {
        final Map<Bytes32, BeaconState> hotStates =
            update.getHotStates().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().state()));
        updater.addHotStates(hotStates);
      }

      @Override
      public String describeHotStatesWriteTime(final long millis) {
        return "";
      }
    };
  }

  @Override
  public Optional<BeaconState> getHotState(final Bytes32 root) {
    return dao.getHotState(root);
  }

  @Override
  public StateDiffVerificationReport verify() {
    throw new IllegalStateException("State diff storage is not active");
  }
}
