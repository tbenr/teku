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

package tech.pegasys.teku.storage.client;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.logic.common.util.ForkChoiceUtil;

/** Runtime storage for record_block_timeliness. */
class BlockTimelinessTracker {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final Supplier<UInt64> genesisTimeMillisSupplier;
  private final Map<Bytes32, boolean[]> blockTimeliness;

  BlockTimelinessTracker(
      final Spec spec,
      final Supplier<UInt64> genesisTimeMillisSupplier,
      final Map<Bytes32, boolean[]> blockTimeliness) {
    this.spec = spec;
    this.genesisTimeMillisSupplier = genesisTimeMillisSupplier;
    this.blockTimeliness = blockTimeliness;
  }

  public void setBlockTimelinessFromArrivalTime(
      final SignedBeaconBlock block, final UInt64 arrivalTimeMillis) {
    final boolean[] existing = blockTimeliness.get(block.getRoot());
    if (existing != null && existing[ForkChoiceUtil.ATTESTATION_TIMELINESS_INDEX]) {
      return;
    }
    setBlockTimeliness(block, computeBlockTimelinessFromArrivalTime(block, arrivalTimeMillis));
  }

  public void setBlockTimeliness(final SignedBeaconBlock block, final boolean[] timeliness) {
    final boolean[] existing = blockTimeliness.get(block.getRoot());
    if (existing != null && existing[ForkChoiceUtil.ATTESTATION_TIMELINESS_INDEX]) {
      return;
    }
    blockTimeliness.put(block.getRoot(), timeliness);
  }

  public void setBlockTimelinessIfAbsentFromArrivalTime(
      final SignedBeaconBlock block, final UInt64 arrivalTimeMillis) {
    if (blockTimeliness.containsKey(block.getRoot())) {
      return;
    }
    blockTimeliness.put(
        block.getRoot(), computeBlockTimelinessFromArrivalTime(block, arrivalTimeMillis));
  }

  public boolean[] computeBlockTimelinessFromArrivalTime(
      final SignedBeaconBlock block, final UInt64 arrivalTimeMillis) {
    final UInt64 genesisTimeMillis = genesisTimeMillisSupplier.get();
    final UInt64 computedSlot =
        spec.getCurrentSlot(arrivalTimeMillis.dividedBy(1000), genesisTimeMillis.dividedBy(1000));
    final Bytes32 root = block.getRoot();
    if (computedSlot.isGreaterThan(block.getMessage().getSlot())) {
      LOG.debug(
          "Block {}:{} is before computed slot {}, timeliness set to false.",
          root,
          block.getSlot(),
          computedSlot);
      return new boolean[] {false};
    }
    final UInt64 slotStartTimeMillis =
        spec.computeTimeMillisAtSlot(computedSlot, genesisTimeMillis);
    final int millisIntoSlot = arrivalTimeMillis.minusMinZero(slotStartTimeMillis).intValue();

    final ForkChoiceUtil forkChoiceUtil = spec.atSlot(computedSlot).getForkChoiceUtil();
    return forkChoiceUtil.computeBlockTimeliness(
        block.getMessage().getSlot(), computedSlot, millisIntoSlot);
  }

  public Optional<boolean[]> getBlockTimeliness(final Bytes32 root) {
    return Optional.ofNullable(blockTimeliness.get(root));
  }

  public boolean isBlockLate(final Bytes32 root) {
    return ForkChoiceUtil.isHeadLate(blockTimeliness.get(root));
  }
}
