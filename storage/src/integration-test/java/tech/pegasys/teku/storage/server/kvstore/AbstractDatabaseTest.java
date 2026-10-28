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

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ONE;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tech.pegasys.teku.bls.BLSKeyGenerator;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.state.AnchorPoint;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.spec.generator.ChainProperties;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.archive.filesystem.FileSystemBlobSidecarsArchiver;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.server.Database;
import tech.pegasys.teku.storage.server.DatabaseContext;
import tech.pegasys.teku.storage.server.StateStorageMode;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;
import tech.pegasys.teku.storage.store.StoreConfig;
import tech.pegasys.teku.storage.store.UpdatableStore;
import tech.pegasys.teku.storage.store.UpdatableStore.StoreTransaction;

/** Fixture shared by the {@link Database} integration tests. */
public abstract class AbstractDatabaseTest {
  protected static final List<BLSKeyPair> VALIDATOR_KEYS = BLSKeyGenerator.generateKeyPairs(3);

  protected Spec spec;
  protected DataStructureUtil dataStructureUtil;
  protected ChainBuilder chainBuilder;
  protected ChainProperties chainProperties;
  protected final List<File> tmpDirectories = new ArrayList<>();
  protected final UInt64 genesisTime = UInt64.valueOf(100);
  protected AnchorPoint genesisAnchor;
  protected SignedBlockAndState genesisBlockAndState;
  protected SignedBlockAndState checkpoint1BlockAndState;
  protected SignedBlockAndState checkpoint2BlockAndState;
  protected SignedBlockAndState checkpoint3BlockAndState;
  protected Checkpoint genesisCheckpoint;
  protected Checkpoint checkpoint1;
  protected Checkpoint checkpoint2;
  protected Checkpoint checkpoint3;
  protected StateStorageMode storageMode;
  protected StorageSystem storageSystem;
  protected Database database;
  protected FileSystemBlobSidecarsArchiver blobSidecarsArchiver;
  protected RecentChainData recentChainData;
  protected UpdatableStore store;
  protected final List<StorageSystem> storageSystems = new ArrayList<>();

  @BeforeEach
  public void setup(final DatabaseContext context) throws IOException {
    setupWithSpec(TestSpecFactory.createMinimalDeneb());
  }

  protected void setupWithSpec(final Spec spec) throws IOException {
    setupWithSpec(spec, VALIDATOR_KEYS);
  }

  protected void setupWithSpec(final Spec spec, final List<BLSKeyPair> validatorKeys)
      throws IOException {
    this.spec = spec;
    this.dataStructureUtil = new DataStructureUtil(spec);
    this.chainBuilder = ChainBuilder.create(spec, validatorKeys);
    this.chainProperties = new ChainProperties(spec);
    final Path blobsArchive = Files.createTempDirectory("blobs");
    tmpDirectories.add(blobsArchive.toFile());
    this.blobSidecarsArchiver = new FileSystemBlobSidecarsArchiver(spec, blobsArchive);
    genesisBlockAndState = chainBuilder.generateGenesis(genesisTime, true);
    genesisCheckpoint = getCheckpointForBlock(genesisBlockAndState.getBlock());
    genesisAnchor = AnchorPoint.fromGenesisState(spec, genesisBlockAndState.getState());
  }

  protected void initialize(final DatabaseContext context, final StateStorageMode storageMode)
      throws IOException {
    final StoreConfig config = StoreConfig.builder().stateCacheSize(32).build();
    createStorageSystem(context, storageMode, config, false);

    // Initialize genesis store
    initGenesis();
  }

  protected void initialize(final DatabaseContext context) throws IOException {
    initialize(context, StateStorageMode.ARCHIVE);
  }

  @AfterEach
  public void reset() throws Exception {
    for (StorageSystem storageSystem : storageSystems) {
      storageSystem.close();
    }
    for (File tmpDirectory : tmpDirectories) {
      MoreFiles.deleteRecursively(tmpDirectory.toPath(), RecursiveDeleteOption.ALLOW_INSECURE);
    }
    tmpDirectories.clear();
  }

  protected void restartStorage() {
    final StorageSystem storage = storageSystem.restarted(storageMode);
    setDefaultStorage(storage);
  }

  protected void commit(final StoreTransaction transaction) {
    assertThat(transaction.commit()).isCompleted();
  }

  protected void addBlocks(final SignedBlockAndState... blocks) {
    addBlocks(Arrays.asList(blocks));
  }

  protected void addBlocks(final List<SignedBlockAndState> blocks) {
    addBlocksAndBlobSidecars(blocks, List.of());
  }

  protected void addBlocksAndBlobSidecars(
      final List<SignedBlockAndState> blocks, final List<BlobSidecar> blobSidecars) {
    final StoreTransaction transaction = recentChainData.startStoreTransaction();
    for (SignedBlockAndState block : blocks) {
      transaction.putBlockAndState(
          block,
          blobSidecars.stream()
              .filter(blobSidecar -> blobSidecar.getBlockRoot().equals(block.getRoot()))
              .toList(),
          spec.calculateBlockCheckpoints(block.getState()));
    }
    commit(transaction);
  }

  protected void justifyAndFinalizeEpoch(final UInt64 epoch, final SignedBlockAndState block) {
    StoreTransaction tx = recentChainData.startStoreTransaction();
    justifyAndFinalizeEpoch(epoch, block, tx);
    assertThat(tx.commit()).isCompleted();
  }

  protected void justifyAndFinalizeEpoch(
      final UInt64 epoch, final SignedBlockAndState block, final StoreTransaction tx) {
    justifyEpoch(epoch, block, tx);
    finalizeEpoch(epoch, block, tx);
  }

  protected void finalizeEpoch(
      final UInt64 epoch, final SignedBlockAndState block, final StoreTransaction transaction) {
    final Checkpoint checkpoint = new Checkpoint(epoch, block.getRoot());
    transaction.setFinalizedCheckpoint(checkpoint, false);
  }

  protected void justifyEpoch(
      final UInt64 epoch, final SignedBlockAndState block, final StoreTransaction transaction) {
    final Checkpoint checkpoint = new Checkpoint(epoch, block.getRoot());
    transaction.setJustifiedCheckpoint(checkpoint);
  }

  protected Checkpoint getCheckpointForBlock(final SignedBeaconBlock block) {
    final UInt64 blockEpoch = spec.computeEpochAtSlot(block.getSlot());
    final UInt64 blockEpochBoundary = spec.computeStartSlotAtEpoch(blockEpoch);
    final UInt64 checkpointEpoch =
        equivalentLongs(block.getSlot(), blockEpochBoundary) ? blockEpoch : blockEpoch.plus(ONE);
    return new Checkpoint(checkpointEpoch, block.getMessage().hashTreeRoot());
  }

  protected boolean equivalentLongs(final UInt64 valA, final UInt64 valB) {
    return valA.compareTo(valB) == 0;
  }

  protected void initGenesis() {
    recentChainData.initializeFromGenesis(genesisBlockAndState.getState(), UInt64.ZERO);
    store = recentChainData.getStore();
  }

  protected void generateCheckpoints() {
    while (chainBuilder.getLatestEpoch().longValue() < 3) {
      chainBuilder.generateNextBlock();
    }

    checkpoint1BlockAndState = chainBuilder.getLatestBlockAndStateAtEpochBoundary(1);
    checkpoint1 = chainBuilder.getCurrentCheckpointForEpoch(1);
    checkpoint2BlockAndState = chainBuilder.getLatestBlockAndStateAtEpochBoundary(2);
    checkpoint2 = chainBuilder.getCurrentCheckpointForEpoch(2);
    checkpoint3BlockAndState = chainBuilder.getLatestBlockAndStateAtEpochBoundary(3);
    checkpoint3 = chainBuilder.getCurrentCheckpointForEpoch(3);
  }

  protected UpdatableStore recreateStore() {
    restartStorage();
    return storageSystem.recentChainData().getStore();
  }

  protected void createStorageSystem(
      final DatabaseContext context,
      final StateStorageMode storageMode,
      final StoreConfig storeConfig,
      final boolean storeNonCanonicalBlocks)
      throws IOException {
    createStorageSystem(context, storageMode, storeConfig, storeNonCanonicalBlocks, 0);
  }

  /**
   * @param stateDiffRetainedSlots the retention window of an archive database with state diff
   *     storage, in slots; 0 keeps all finalized history
   */
  protected void createStorageSystem(
      final DatabaseContext context,
      final StateStorageMode storageMode,
      final StoreConfig storeConfig,
      final boolean storeNonCanonicalBlocks,
      final long stateDiffRetainedSlots)
      throws IOException {
    this.storageMode = storageMode;
    if (context.isInMemoryStorage()) {
      setDefaultStorage(
          context.createInMemoryStorage(
              spec, storageMode, storeConfig, storeNonCanonicalBlocks, stateDiffRetainedSlots));
    } else {
      final Path tmpDir = Files.createTempDirectory("storageTest");
      tmpDirectories.add(tmpDir.toFile());
      setDefaultStorage(
          context.createFileBasedStorage(
              spec,
              tmpDir,
              storageMode,
              storeConfig,
              storeNonCanonicalBlocks,
              stateDiffRetainedSlots));
    }
  }

  protected KvStoreCombinedDao dao() {
    return ((KvStoreDatabase) database).dao;
  }

  protected void setDefaultStorage(final StorageSystem storageSystem) {
    this.storageSystem = storageSystem;
    database = storageSystem.database();
    recentChainData = storageSystem.recentChainData();
    storageSystems.add(storageSystem);
  }
}
