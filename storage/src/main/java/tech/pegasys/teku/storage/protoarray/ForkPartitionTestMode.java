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

package tech.pegasys.teku.storage.protoarray;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * TEST ONLY. Forces this node onto its own fork to create several contending, non-finalizing forks
 * across a small devnet.
 *
 * <p>While the control file exists, fork choice only accepts as head candidates the blocks this
 * node published itself (through the validator API) and any block up to the slot at which the mode
 * was switched on. Blocks from other nodes are still imported and still receive votes, but are
 * never viable heads, so this node's validators only attest to and build on its own fork. Deleting
 * the control file restores normal fork choice.
 *
 * <p>The control file is {@code $TEKU_FORK_PARTITION_FILE}, or {@value #DEFAULT_CONTROL_FILE}.
 * State is in memory only: restarting a node during a partition makes it forget its own blocks.
 */
public final class ForkPartitionTestMode {
  private static final Logger LOG = LogManager.getLogger();

  static final String DEFAULT_CONTROL_FILE = "/tmp/teku-fork-partition";

  private static final Path CONTROL_FILE =
      Path.of(
          Optional.ofNullable(System.getenv("TEKU_FORK_PARTITION_FILE"))
              .orElse(DEFAULT_CONTROL_FILE));

  private static final Set<Bytes32> LOCAL_BLOCK_ROOTS = ConcurrentHashMap.newKeySet();
  private static volatile boolean active = false;
  private static volatile UInt64 activationSlot = UInt64.ZERO;

  private ForkPartitionTestMode() {}

  /** Re-reads the control file. Called before each head recomputation, under the fork lock. */
  static void refresh(final UInt64 currentSlot) {
    final boolean shouldBeActive = Files.exists(CONTROL_FILE);
    if (shouldBeActive == active) {
      return;
    }
    if (shouldBeActive) {
      activationSlot = currentSlot;
      active = true;
      LOG.warn(
          "Fork partition test mode ENABLED at slot {} ({} present): only locally produced blocks"
              + " after this slot can become head",
          currentSlot,
          CONTROL_FILE);
    } else {
      active = false;
      LOG.warn(
          "Fork partition test mode DISABLED at slot {} ({} removed): normal fork choice restored",
          currentSlot,
          CONTROL_FILE);
      LOCAL_BLOCK_ROOTS.clear();
    }
  }

  /** Records a block produced by this node. Must be called before the block is imported. */
  public static void recordLocalBlock(final Bytes32 blockRoot) {
    LOCAL_BLOCK_ROOTS.add(blockRoot);
  }

  static boolean isEligibleForHead(final ProtoNode node) {
    return !active
        || node.getBlockSlot().isLessThanOrEqualTo(activationSlot)
        || LOCAL_BLOCK_ROOTS.contains(node.getBlockRoot());
  }
}
