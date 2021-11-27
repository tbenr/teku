/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.forkchoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.core.ChainBuilder;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.time.StubTimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfigLoader;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.execution.PowBlock;
import tech.pegasys.teku.spec.executionengine.ExecutionEngineChannel;
import tech.pegasys.teku.spec.logic.common.block.AbstractBlockProcessor;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.storageSystem.InMemoryStorageSystemBuilder;
import tech.pegasys.teku.storage.storageSystem.StorageSystem;

public class TerminalPowBlockMonitorTest {
  private static final UInt64 MERGE_FORK_EPOCH = UInt64.ONE;
  private static final UInt256 TTD = UInt256.valueOf(10_000_000);
  private static final Bytes32 TERMINAL_BLOCK_HASH = Bytes32.random();
  private static final UInt64 TERMINAL_BLOCK_EPOCH = UInt64.valueOf(2);

  private final ExecutionEngineChannel executionEngine = mock(ExecutionEngineChannel.class);
  private final StubTimeProvider timeProvider = StubTimeProvider.withTimeInSeconds(10_000);
  private final StubAsyncRunner asyncRunner = new StubAsyncRunner(timeProvider);
  private final ForkChoiceNotifier forkChoiceNotifier = mock(ForkChoiceNotifier.class);

  private Spec spec;
  private DataStructureUtil dataStructureUtil;
  private StorageSystem storageSystem;
  private RecentChainData recentChainData;
  private TerminalPowBlockMonitor terminalPowBlockMonitor;

  @BeforeAll
  public static void initSession() {
    AbstractBlockProcessor.BLS_VERIFY_DEPOSIT = false;
  }

  @AfterAll
  public static void resetSession() {
    AbstractBlockProcessor.BLS_VERIFY_DEPOSIT = true;
  }

  private void setUpTerminalBlockHashConfig() {
    spec =
        TestSpecFactory.createMerge(
            SpecConfigLoader.loadConfig(
                "minimal",
                phase0Builder ->
                    phase0Builder
                        .altairBuilder(altairBuilder -> altairBuilder.altairForkEpoch(UInt64.ZERO))
                        .mergeBuilder(
                            mergeBuilder ->
                                mergeBuilder
                                    .mergeForkEpoch(MERGE_FORK_EPOCH)
                                    .terminalBlockHash(TERMINAL_BLOCK_HASH)
                                    .terminalBlockHashActivationEpoch(TERMINAL_BLOCK_EPOCH))));

    setUpCommon();
  }

  private void setUpTTDConfig() {
    spec =
        TestSpecFactory.createMerge(
            SpecConfigLoader.loadConfig(
                "minimal",
                phase0Builder ->
                    phase0Builder
                        .altairBuilder(altairBuilder -> altairBuilder.altairForkEpoch(UInt64.ZERO))
                        .mergeBuilder(
                            mergeBuilder ->
                                mergeBuilder
                                    .mergeForkEpoch(MERGE_FORK_EPOCH)
                                    .terminalTotalDifficulty(TTD))));

    setUpCommon();
  }

  private void setUpCommon() {
    dataStructureUtil = new DataStructureUtil(spec);
    storageSystem = InMemoryStorageSystemBuilder.buildDefault(spec);
    storageSystem.chainUpdater().initializeGenesis(false);
    recentChainData = storageSystem.recentChainData();

    terminalPowBlockMonitor =
        new TerminalPowBlockMonitor(
            executionEngine, spec, recentChainData, forkChoiceNotifier, asyncRunner);
  }

  private void goToSlot(UInt64 slot) {
    storageSystem
        .chainUpdater()
        .updateBestBlock(storageSystem.chainUpdater().advanceChainUntil(slot));
  }

  private void doMerge(Bytes32 terminalBlockHash) {
    SignedBlockAndState newBlockWithExecutionPayloadAtopTerminalBlock =
        storageSystem
            .chainUpdater()
            .chainBuilder
            .generateBlockAtSlot(
                recentChainData.getHeadSlot().plus(1),
                ChainBuilder.BlockOptions.create()
                    .setTransactions(dataStructureUtil.randomBytes(40))
                    .setTerminalBlockHash(terminalBlockHash));

    storageSystem.chainUpdater().updateBestBlock(newBlockWithExecutionPayloadAtopTerminalBlock);
  }

  @Test
  public void shouldPerformTerminalBlockDetectionByTTD() {
    setUpTTDConfig();

    terminalPowBlockMonitor.start();

    // NOT YET MERGE FORK
    goToSlot(UInt64.ONE);

    assertThat(terminalPowBlockMonitor.isRunning()).isTrue();
    assertThat(asyncRunner.hasDelayedActions()).isTrue();

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT MERGE FORK, TTD not reached
    Bytes32 headBlockHash = dataStructureUtil.randomBytes32();

    goToSlot(MERGE_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(
                new PowBlock(headBlockHash, dataStructureUtil.randomBytes32(), TTD.subtract(10))));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(1)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT MERGE FORK, TTD reached
    headBlockHash = dataStructureUtil.randomBytes32();
    when(executionEngine.getPowChainHead())
        .thenReturn(
            completedFuture(new PowBlock(headBlockHash, dataStructureUtil.randomBytes32(), TTD)));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(2)).getPowChainHead();
    verify(forkChoiceNotifier, times(1)).onTerminalBlockReached(headBlockHash);

    // MERGE Completed
    doMerge(headBlockHash);

    asyncRunner.executeQueuedActions();

    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();

    // final check
    verify(executionEngine, times(0)).getPowBlock(any());
  }

  @Test
  void shouldPerformTerminalBlockDetectionByTerminalBlockHash() {
    setUpTerminalBlockHashConfig();

    terminalPowBlockMonitor.start();

    // NOT YET MERGE FORK
    goToSlot(UInt64.ONE);

    assertThat(terminalPowBlockMonitor.isRunning()).isTrue();
    assertThat(asyncRunner.hasDelayedActions()).isTrue();

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowChainHead();
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT MERGE FORK, Terminal Bloch Epoch not reached
    goToSlot(MERGE_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(0)).getPowBlock(any());
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT Terminal Bloch Epoch, Terminal Block Hash not found
    goToSlot(TERMINAL_BLOCK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));
    when(executionEngine.getPowBlock(TERMINAL_BLOCK_HASH))
        .thenReturn(completedFuture(Optional.empty()));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(1)).getPowBlock(TERMINAL_BLOCK_HASH);
    verify(forkChoiceNotifier, times(0)).onTerminalBlockReached(any());

    // AT Terminal Bloch Epoch, Terminal Block Hash found
    when(executionEngine.getPowBlock(TERMINAL_BLOCK_HASH))
        .thenReturn(
            completedFuture(
                Optional.of(
                    new PowBlock(
                        TERMINAL_BLOCK_HASH, dataStructureUtil.randomBytes32(), UInt256.ONE))));

    asyncRunner.executeQueuedActions();

    verify(executionEngine, times(2)).getPowBlock(TERMINAL_BLOCK_HASH);
    verify(forkChoiceNotifier, times(1)).onTerminalBlockReached(TERMINAL_BLOCK_HASH);

    // MERGE Completed
    doMerge(TERMINAL_BLOCK_HASH);
    asyncRunner.executeQueuedActions();
    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();

    // final check
    verify(executionEngine, times(0)).getPowChainHead();
  }

  @Test
  void shouldImmediatelyStopWhenMergeCompleted() {
    setUpTerminalBlockHashConfig();
    goToSlot(MERGE_FORK_EPOCH.times(spec.getGenesisSpecConfig().getSlotsPerEpoch()));
    doMerge(TERMINAL_BLOCK_HASH);

    terminalPowBlockMonitor.start();

    asyncRunner.executeQueuedActions();

    assertThat(terminalPowBlockMonitor.isRunning()).isFalse();
  }
}
