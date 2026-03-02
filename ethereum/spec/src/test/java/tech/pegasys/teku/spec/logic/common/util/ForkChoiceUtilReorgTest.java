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

package tech.pegasys.teku.spec.logic.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceReorgContext;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyForkChoiceStrategy;
import tech.pegasys.teku.spec.datastructures.forkchoice.ReadOnlyStore;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.helpers.BeaconStateAccessors;
import tech.pegasys.teku.spec.logic.common.helpers.MiscHelpers;
import tech.pegasys.teku.spec.logic.common.statetransition.epoch.EpochProcessor;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.EpochProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.SlotProcessingException;
import tech.pegasys.teku.spec.logic.common.util.ForkChoiceUtil.BlockTimeliness;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ForkChoiceUtilReorgTest {
  private final Spec spec = TestSpecFactory.createDefault();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final UInt64 slot = UInt64.ONE;
  private final int millisPerSlot = spec.getGenesisSpecConfig().getSlotDurationMillis();

  private SignedBlockAndState signedBlockAndState;
  private ForkChoiceUtilHarness forkChoiceUtil;
  private ReadOnlyStore store;
  private ReadOnlyForkChoiceStrategy forkChoiceStrategy;
  private TestForkChoiceReorgContext context;
  private UInt64 genesisTime;
  private UInt64 genesisTimeMillis;

  @BeforeEach
  void setup() {
    signedBlockAndState = dataStructureUtil.randomSignedBlockAndState(slot);
    genesisTime = signedBlockAndState.getState().getGenesisTime();
    genesisTimeMillis = genesisTime.times(1000);

    final SpecVersion specVersion = spec.atSlot(slot);
    forkChoiceUtil =
        new ForkChoiceUtilHarness(
            specVersion.getConfig(),
            specVersion.beaconStateAccessors(),
            specVersion.getEpochProcessor(),
            specVersion.getAttestationUtil(),
            specVersion.miscHelpers());

    store = mock(ReadOnlyStore.class);
    forkChoiceStrategy = mock(ReadOnlyForkChoiceStrategy.class);
    context = new TestForkChoiceReorgContext(store);

    when(store.getForkChoiceStrategy()).thenReturn(forkChoiceStrategy);
    when(store.getGenesisTime()).thenReturn(genesisTime);
    when(store.getGenesisTimeMillis()).thenReturn(genesisTimeMillis);
    when(store.getTimeInMillis()).thenReturn(genesisTimeMillis);
    when(store.getTimeSeconds()).thenReturn(genesisTime);
    when(store.getFinalizedCheckpoint())
        .thenReturn(dataStructureUtil.randomCheckpoint(UInt64.ZERO));
    when(store.getProposerBoostRoot()).thenReturn(Optional.empty());
    when(store.getBlockIfAvailable(any())).thenReturn(Optional.empty());
    when(store.getBlockStateIfAvailable(any())).thenReturn(Optional.empty());
    when(store.isFfgCompetitive(any(), any())).thenReturn(Optional.empty());
    when(store.getReorgThreshold()).thenReturn(UInt64.ONE);
    when(store.getParentThreshold()).thenReturn(UInt64.ONE);
    when(forkChoiceStrategy.blockSlot(any())).thenReturn(Optional.empty());
  }

  @Test
  void isProposingOnTime_shouldBeTrueAtSlotStart() {
    when(store.getTimeInMillis()).thenReturn(genesisTimeMillis.plus(millisPerSlot));
    assertThat(forkChoiceUtil.isProposingOnTime(store, slot)).isTrue();
  }

  @Test
  void isProposingOnTime_shouldBeFalseAfterCutoff() {
    when(store.getTimeInMillis()).thenReturn(genesisTimeMillis.plus(millisPerSlot + 1001));
    assertThat(forkChoiceUtil.isProposingOnTime(store, slot)).isFalse();
  }

  @Test
  void getProposerHead_shouldShortCircuitWhenHeadIsTimely() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), true);

    assertThat(forkChoiceUtil.getProposerHead(context, signedBlockAndState.getRoot(), UInt64.ONE))
        .isEqualTo(signedBlockAndState.getRoot());
  }

  @Test
  void getProposerHead_shouldShortCircuitWhenProposerBoostIsActive() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), false);
    when(store.getProposerBoostRoot()).thenReturn(Optional.of(dataStructureUtil.randomBytes32()));

    assertThat(forkChoiceUtil.getProposerHead(context, signedBlockAndState.getRoot(), UInt64.ONE))
        .isEqualTo(signedBlockAndState.getRoot());
  }

  @Test
  void getProposerHead_shouldReturnParentWhenHeadIsWeakAndParentStrong() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), false);
    withStableForkChoice();
    withFfgCompetitive();
    withParentSlot(Optional.of(UInt64.ZERO));
    forkChoiceUtil.headWeak = true;
    forkChoiceUtil.parentStrong = true;

    assertThat(
            forkChoiceUtil.getProposerHead(
                context, signedBlockAndState.getRoot(), UInt64.valueOf(2)))
        .isEqualTo(signedBlockAndState.getParentRoot());
  }

  @Test
  void getProposerHead_shouldKeepHeadWhenParentIsWeak() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), false);
    withStableForkChoice();
    withFfgCompetitive();
    withParentSlot(Optional.of(UInt64.ZERO));
    forkChoiceUtil.headWeak = true;
    forkChoiceUtil.parentStrong = false;

    assertThat(
            forkChoiceUtil.getProposerHead(
                context, signedBlockAndState.getRoot(), UInt64.valueOf(2)))
        .isEqualTo(signedBlockAndState.getRoot());
  }

  @Test
  void shouldOverrideForkChoiceUpdate_shouldReturnFalseWhenHeadIsTimely() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), true);

    assertThat(
            forkChoiceUtil.shouldOverrideForkChoiceUpdate(context, signedBlockAndState.getRoot()))
        .isFalse();
  }

  @Test
  void shouldOverrideForkChoiceUpdate_shouldReturnFalseWhenParentSlotMissing() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), false);
    withCurrentSlot(UInt64.ONE);
    withStableForkChoice();
    withFfgCompetitive();

    assertThat(
            forkChoiceUtil.shouldOverrideForkChoiceUpdate(context, signedBlockAndState.getRoot()))
        .isFalse();
  }

  @Test
  void shouldOverrideForkChoiceUpdate_shouldReturnTrueWhenAllChecksPass() {
    withHeadBlock();
    context.setBlockTimeliness(signedBlockAndState.getRoot(), false);
    withCurrentSlot(UInt64.valueOf(2));
    withStableForkChoice();
    withFfgCompetitive();
    withParentSlot(Optional.of(UInt64.ZERO));
    forkChoiceUtil.headWeak = true;
    forkChoiceUtil.parentStrong = true;
    when(store.getBlockStateIfAvailable(any()))
        .thenReturn(Optional.of(signedBlockAndState.getState()));
    context.validatorConnected = true;

    assertThat(
            forkChoiceUtil.shouldOverrideForkChoiceUpdate(context, signedBlockAndState.getRoot()))
        .isTrue();
  }

  @Test
  void shouldOverrideFcuCheckProposerPreState_shouldReturnFalseWhenParentStateMissing() {
    when(store.getBlockStateIfAvailable(any())).thenReturn(Optional.empty());

    assertThat(
            forkChoiceUtil.shouldOverrideFcuCheckProposerPreState(
                context, UInt64.valueOf(2), dataStructureUtil.randomBytes32()))
        .isFalse();
  }

  @Test
  void shouldOverrideFcuCheckProposerPreState_shouldReturnFalseWhenValidatorDisconnected() {
    when(store.getBlockStateIfAvailable(any()))
        .thenReturn(Optional.of(signedBlockAndState.getState()));
    context.validatorConnected = false;

    assertThat(
            forkChoiceUtil.shouldOverrideFcuCheckProposerPreState(
                context, UInt64.valueOf(2), dataStructureUtil.randomBytes32()))
        .isFalse();
  }

  private void withHeadBlock() {
    when(store.getBlockIfAvailable(any())).thenReturn(signedBlockAndState.getSignedBeaconBlock());
  }

  private void withParentSlot(final Optional<UInt64> maybeSlot) {
    when(forkChoiceStrategy.blockSlot(signedBlockAndState.getParentRoot())).thenReturn(maybeSlot);
  }

  private void withFfgCompetitive() {
    when(store.isFfgCompetitive(any(), any())).thenReturn(Optional.of(true));
  }

  private void withStableForkChoice() {
    when(store.getFinalizedCheckpoint())
        .thenReturn(dataStructureUtil.randomCheckpoint(UInt64.ZERO));
    when(store.getProposerBoostRoot()).thenReturn(Optional.empty());
  }

  private void withCurrentSlot(final UInt64 currentSlot) {
    final UInt64 currentTimeMillis = genesisTimeMillis.plus(currentSlot.times(millisPerSlot));
    when(store.getTimeInMillis()).thenReturn(currentTimeMillis);
    when(store.getTimeSeconds()).thenReturn(currentTimeMillis.dividedBy(1000));
  }

  private static class TestForkChoiceReorgContext implements ForkChoiceReorgContext {
    private final ReadOnlyStore store;
    private final Map<Bytes32, BlockTimeliness> blockTimeliness = new HashMap<>();
    private boolean validatorConnected = true;

    private TestForkChoiceReorgContext(final ReadOnlyStore store) {
      this.store = store;
    }

    @Override
    public ReadOnlyStore getStore() {
      return store;
    }

    @Override
    public Optional<BlockTimeliness> getBlockTimeliness(final Bytes32 root) {
      return Optional.ofNullable(blockTimeliness.get(root));
    }

    @Override
    public boolean isValidatorConnected(final int validatorIndex, final UInt64 slot) {
      return validatorConnected;
    }

    @Override
    public BeaconState processSlots(final BeaconState state, final UInt64 slot)
        throws SlotProcessingException, EpochProcessingException {
      return state;
    }

    private void setBlockTimeliness(final Bytes32 root, final boolean isTimely) {
      blockTimeliness.put(root, new BlockTimeliness(isTimely, false));
    }
  }

  private static class ForkChoiceUtilHarness extends ForkChoiceUtil {
    private boolean headWeak;
    private boolean parentStrong;

    private ForkChoiceUtilHarness(
        final SpecConfig specConfig,
        final BeaconStateAccessors beaconStateAccessors,
        final EpochProcessor epochProcessor,
        final AttestationUtil attestationUtil,
        final MiscHelpers miscHelpers) {
      super(specConfig, beaconStateAccessors, epochProcessor, attestationUtil, miscHelpers);
    }

    @Override
    public boolean isHeadWeak(
        final ReadOnlyStore store, final Bytes32 root, final UInt64 reorgThreshold) {
      return headWeak;
    }

    @Override
    public boolean isParentStrong(
        final ReadOnlyStore store, final Bytes32 parentRoot, final UInt64 parentThreshold) {
      return parentStrong;
    }

    @Override
    protected int getProposerIndex(final BeaconState proposerPreState, final UInt64 proposalSlot) {
      return 1;
    }
  }
}
