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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.MockKvStoreInstance;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.CombinedKvStoreDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDaoAdapter;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedKvStoreDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedStateSnapshotStorageLogic;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4HotKvStoreDao;
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreColumn;
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreVariable;
import tech.pegasys.teku.storage.server.kvstore.schema.SchemaFinalizedSnapshotStateAdapter;
import tech.pegasys.teku.storage.server.kvstore.schema.SchemaHotAdapter;
import tech.pegasys.teku.storage.server.kvstore.schema.V6SchemaCombinedSnapshot;

class StateDiffDaoTest {

  private final Spec spec = TestSpecFactory.createMinimalElectra();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final V6SchemaCombinedSnapshot schema = V6SchemaCombinedSnapshot.createV6(spec);
  private final KvStoreAccessor db =
      MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables());
  private final KvStoreCombinedDao dao =
      new CombinedKvStoreDao<>(
          db, schema, new V4FinalizedStateSnapshotStorageLogic<>(1), new NoOpMetricsSystem());

  @Test
  void putAndGetNode() {
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final Bytes record = dataStructureUtil.randomBytes(200);

    update(dao, updater -> updater.putStateDiffNode(blockRoot, record));

    assertThat(dao.getStateDiffNode(blockRoot)).contains(record);
    assertThat(dao.getStateDiffNode(dataStructureUtil.randomBytes32())).isEmpty();
  }

  @Test
  void deleteNode() {
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final Bytes32 otherRoot = dataStructureUtil.randomBytes32();
    update(
        dao,
        updater -> {
          updater.putStateDiffNode(blockRoot, dataStructureUtil.randomBytes(10));
          updater.putStateDiffNode(otherRoot, dataStructureUtil.randomBytes(10));
        });

    update(dao, updater -> updater.deleteStateDiffNode(blockRoot));

    assertThat(dao.getStateDiffNode(blockRoot)).isEmpty();
    assertThat(dao.getStateDiffNode(otherRoot)).isPresent();
  }

  @Test
  void putAndGetSupernode() {
    final Bytes32 hash = dataStructureUtil.randomBytes32();
    final Bytes ssz = dataStructureUtil.randomBytes(512);

    update(dao, updater -> updater.putStateSupernode(hash, ssz));

    assertThat(dao.getStateSupernode(hash)).contains(ssz);
    assertThat(dao.getStateSupernodeRefCount(hash)).isEmpty();
    assertThat(dao.getStateSupernode(dataStructureUtil.randomBytes32())).isEmpty();
  }

  @Test
  void refCountRoundTrip() {
    final Bytes32 hash = dataStructureUtil.randomBytes32();
    final Bytes ssz = dataStructureUtil.randomBytes(512);
    update(
        dao,
        updater -> {
          updater.putStateSupernode(hash, ssz);
          updater.setStateSupernodeRefCount(hash, UInt64.valueOf(2));
        });
    assertThat(dao.getStateSupernodeRefCount(hash)).contains(UInt64.valueOf(2));

    update(dao, updater -> updater.setStateSupernodeRefCount(hash, UInt64.valueOf(7)));

    assertThat(dao.getStateSupernodeRefCount(hash)).contains(UInt64.valueOf(7));
    assertThat(dao.getStateSupernode(hash)).contains(ssz);
  }

  @Test
  void deleteSupernodeRemovesRefCount() {
    final Bytes32 hash = dataStructureUtil.randomBytes32();
    final Bytes32 otherHash = dataStructureUtil.randomBytes32();
    update(
        dao,
        updater -> {
          updater.putStateSupernode(hash, dataStructureUtil.randomBytes(64));
          updater.setStateSupernodeRefCount(hash, UInt64.ONE);
          updater.putStateSupernode(otherHash, dataStructureUtil.randomBytes(64));
          updater.setStateSupernodeRefCount(otherHash, UInt64.ONE);
        });

    update(dao, updater -> updater.deleteStateSupernode(hash));

    assertThat(dao.getStateSupernode(hash)).isEmpty();
    assertThat(dao.getStateSupernodeRefCount(hash)).isEmpty();
    assertThat(dao.getStateSupernode(otherHash)).isPresent();
    assertThat(dao.getStateSupernodeRefCount(otherHash)).contains(UInt64.ONE);
  }

  @Test
  void modeVariableRoundTrip() {
    final Bytes marker = Bytes.fromHexString("0x01");
    assertThat(dao.getStateDiffMode()).isEmpty();

    update(dao, updater -> updater.setStateDiffMode(marker));

    assertThat(dao.getStateDiffMode()).contains(marker);
  }

  @Test
  void deleteLatestFinalizedStateRemovesVariable() {
    update(
        dao, updater -> updater.setLatestFinalizedState(dataStructureUtil.randomBeaconState(16)));
    assertThat(dao.getLatestFinalizedState()).isPresent();

    update(dao, HotUpdater::deleteLatestFinalizedState);

    assertThat(dao.getLatestFinalizedState()).isEmpty();
  }

  @Test
  void columnIdsAreTenElevenThirteen() {
    assertThat(schema.getColumnStateDiffNodes().getId()).isEqualTo(KvStoreColumn.asColumnId(10));
    assertThat(schema.getColumnStateSupernodes().getId()).isEqualTo(KvStoreColumn.asColumnId(11));
    // 12 is the hot blob sidecar column of V4 databases (finalized offset 0)
    assertThat(schema.getColumnStateSupernodeRefCounts().getId())
        .isEqualTo(KvStoreColumn.asColumnId(13));
    assertThat(schema.getVariableStateDiffMode().getId())
        .isEqualTo(KvStoreVariable.asVariableId(13));
    assertThat(schema.getColumnMap())
        .containsEntry("STATE_DIFF_NODES", schema.getColumnStateDiffNodes())
        .containsEntry("STATE_SUPERNODES", schema.getColumnStateSupernodes())
        .containsEntry("STATE_SUPERNODE_REFCOUNTS", schema.getColumnStateSupernodeRefCounts());
    assertThat(schema.getVariableMap())
        .containsEntry("STATE_DIFF_MODE", schema.getVariableStateDiffMode());
    assertThat(schema.getAllColumns().stream().map(KvStoreColumn::getId))
        .doesNotHaveDuplicates()
        .doesNotContainAnyElementsOf(schema.getDeletedColumnIds());
    assertThat(schema.getAllVariables().stream().map(KvStoreVariable::getId))
        .doesNotHaveDuplicates()
        .doesNotContainAnyElementsOf(schema.getDeletedVariableIds());
  }

  @Test
  void columnsAreStaticData() {
    assertThat(schema.getColumnStateDiffNodes().containsStaticData()).isTrue();
    assertThat(schema.getColumnStateSupernodes().containsStaticData()).isTrue();
    assertThat(schema.getColumnStateSupernodeRefCounts().containsStaticData()).isFalse();
  }

  @Test
  void v4HotDatabaseStoresStateDiffData() {
    final V6SchemaCombinedSnapshot v4Schema = V6SchemaCombinedSnapshot.createV4(spec);
    final SchemaHotAdapter hotSchema = v4Schema.asSchemaHot();
    final SchemaFinalizedSnapshotStateAdapter finalizedSchema = v4Schema.asSchemaFinalized();
    assertThat(hotSchema.getColumnMap())
        .containsEntry("STATE_DIFF_NODES", v4Schema.getColumnStateDiffNodes())
        .containsEntry("STATE_SUPERNODES", v4Schema.getColumnStateSupernodes())
        .containsEntry("STATE_SUPERNODE_REFCOUNTS", v4Schema.getColumnStateSupernodeRefCounts());
    assertThat(hotSchema.getVariableMap())
        .containsEntry("STATE_DIFF_MODE", v4Schema.getVariableStateDiffMode());
    // The V4 hot store is opened with these ids alone (the hot adapter declares no deleted ids)
    assertThat(hotSchema.getDeletedColumnIds()).isEmpty();
    assertThat(hotSchema.getAllColumns().stream().map(KvStoreColumn::getId))
        .doesNotHaveDuplicates();
    assertThat(hotSchema.getAllVariables().stream().map(KvStoreVariable::getId))
        .doesNotHaveDuplicates();
    final KvStoreCombinedDao v4Dao =
        new KvStoreCombinedDaoAdapter(
            new V4HotKvStoreDao(
                MockKvStoreInstance.createEmpty(
                    hotSchema.getAllColumns(), hotSchema.getAllVariables()),
                hotSchema),
            new V4FinalizedKvStoreDao(
                new NoOpMetricsSystem(),
                MockKvStoreInstance.createEmpty(
                    finalizedSchema.getAllColumns(), finalizedSchema.getAllVariables()),
                finalizedSchema,
                new V4FinalizedStateSnapshotStorageLogic<>(1)));
    final Bytes32 blockRoot = dataStructureUtil.randomBytes32();
    final Bytes32 hash = dataStructureUtil.randomBytes32();
    final Bytes record = dataStructureUtil.randomBytes(64);
    final Bytes supernode = dataStructureUtil.randomBytes(64);
    final Bytes marker = Bytes.fromHexString("0x01");

    update(
        v4Dao,
        updater -> {
          updater.putStateDiffNode(blockRoot, record);
          updater.putStateSupernode(hash, supernode);
          updater.setStateSupernodeRefCount(hash, UInt64.valueOf(3));
          updater.setStateDiffMode(marker);
          updater.setLatestFinalizedState(dataStructureUtil.randomBeaconState(16));
        });

    assertThat(v4Dao.getStateDiffNode(blockRoot)).contains(record);
    assertThat(v4Dao.getStateSupernode(hash)).contains(supernode);
    assertThat(v4Dao.getStateSupernodeRefCount(hash)).contains(UInt64.valueOf(3));
    assertThat(v4Dao.getStateDiffMode()).contains(marker);
    assertThat(v4Dao.getLatestFinalizedState()).isPresent();

    update(
        v4Dao,
        updater -> {
          updater.deleteStateDiffNode(blockRoot);
          updater.deleteStateSupernode(hash);
          updater.deleteLatestFinalizedState();
        });

    assertThat(v4Dao.getStateDiffNode(blockRoot)).isEmpty();
    assertThat(v4Dao.getStateSupernode(hash)).isEmpty();
    assertThat(v4Dao.getStateSupernodeRefCount(hash)).isEmpty();
    assertThat(v4Dao.getLatestFinalizedState()).isEmpty();
  }

  @Test
  void variablesPointToStateDiffsForLatestFinalizedStateWhenActive() {
    assertThat(dao.getVariables().get("LATEST_FINALIZED_STATE")).isEmpty();

    update(dao, updater -> updater.setStateDiffMode(Bytes.of(1)));

    assertThat(dao.getVariables().get("LATEST_FINALIZED_STATE"))
        .contains(
            "state diff storage active: reconstruct via verify-state-diffs or "
                + "get-latest-finalized-state");
  }

  private static void update(final KvStoreCombinedDao dao, final Consumer<HotUpdater> update) {
    try (final HotUpdater updater = dao.hotUpdater()) {
      update.accept(updater);
      updater.commit();
    }
  }
}
