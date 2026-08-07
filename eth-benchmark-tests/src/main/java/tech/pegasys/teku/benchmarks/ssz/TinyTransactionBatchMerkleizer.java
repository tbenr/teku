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

package tech.pegasys.teku.benchmarks.ssz;

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.crypto.BatchSha256;
import tech.pegasys.teku.infrastructure.crypto.Hash;
import tech.pegasys.teku.infrastructure.crypto.Sha256;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafNode;
import tech.pegasys.teku.infrastructure.ssz.tree.ProgressiveTreeUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedProgressiveByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;

/**
 * Benchmark-only merkleizer for lists of one-byte transactions.
 *
 * <p>Element roots and balanced outer-tree tiles use the batch provider. Tile roots, progressive
 * spines, and length mix-ins use JCA because they expose too little parallelism to amortize an FFM
 * call.
 */
public final class TinyTransactionBatchMerkleizer {
  private static final int ROOT_SIZE = 32;
  private static final int PAIR_SIZE = 64;

  private final BatchSha256 hasher;
  private final int tileSize;
  private final Sha256 fallback = Hash.getSha256Instance();

  public TinyTransactionBatchMerkleizer(final BatchSha256 hasher, final int tileSize) {
    checkArgument(
        tileSize > 0 && Integer.bitCount(tileSize) == 1,
        "tileSize must be a positive power of two");
    this.hasher = hasher;
    this.tileSize = tileSize;
  }

  public Bytes32 hashFixed(
      final SszPackedByteListsNode node, final int elementDataDepth, final int listDepth) {
    checkArgument(elementDataDepth >= 0, "elementDataDepth must be non-negative");
    final PackedOneByteTransactions transactions =
        validate(node.getSszBytes(), node.getElementCount());
    checkArgument(transactions.count() <= 1L << listDepth, "transaction count exceeds list depth");
    try (Arena arena = Arena.ofConfined()) {
      final Scratch scratch = allocateScratch(arena);
      final Bytes32 dataRoot =
          hashBalancedRange(
              transactions, 0, transactions.count(), listDepth, elementDataDepth, false, scratch);
      return hashPair(dataRoot, lengthRoot(transactions.count()));
    }
  }

  public Bytes32 hashProgressive(final SszPackedProgressiveByteListsNode node) {
    final PackedOneByteTransactions transactions =
        validate(node.getSszBytes(), node.getElementCount());
    try (Arena arena = Arena.ofConfined()) {
      final Scratch scratch = allocateScratch(arena);
      final List<Bytes32> levelRoots = new ArrayList<>();
      int level = 0;
      long levelStart = 0;
      while (levelStart < transactions.count()) {
        final long levelCapacity = ProgressiveTreeUtil.levelCapacity(level);
        final int levelCount =
            Math.toIntExact(Math.min(levelCapacity, transactions.count() - levelStart));
        levelRoots.add(
            hashBalancedRange(
                transactions,
                Math.toIntExact(levelStart),
                levelCount,
                ProgressiveTreeUtil.levelDepth(level),
                0,
                true,
                scratch));
        levelStart += levelCapacity;
        level++;
      }

      Bytes32 dataRoot = LeafNode.EMPTY_LEAF.hashTreeRoot();
      for (int i = levelRoots.size() - 1; i >= 0; i--) {
        dataRoot = hashPair(levelRoots.get(i), dataRoot);
      }
      return hashPair(dataRoot, lengthRoot(transactions.count()));
    }
  }

  public long nativeScratchBytes() {
    return Math.multiplyExact(tileSize, (long) PAIR_SIZE + ROOT_SIZE);
  }

  private Bytes32 hashBalancedRange(
      final PackedOneByteTransactions transactions,
      final int firstTransaction,
      final int transactionCount,
      final int targetDepth,
      final int elementDataDepth,
      final boolean progressiveElements,
      final Scratch scratch) {
    checkArgument(transactionCount > 0, "transactionCount must be positive");
    final long targetWidth = 1L << targetDepth;
    checkArgument(transactionCount <= targetWidth, "range exceeds target depth");

    final RootAccumulator accumulator = new RootAccumulator(targetDepth);
    int processed = 0;
    long paddedCount = 0;
    while (processed < transactionCount) {
      final int chunkCount = Math.min(tileSize, transactionCount - processed);
      final int chunkWidth = ceilingPowerOfTwo(chunkCount);
      final Bytes32 chunkRoot =
          hashTile(
              transactions,
              firstTransaction + processed,
              chunkCount,
              chunkWidth,
              elementDataDepth,
              progressiveElements,
              scratch);
      accumulator.append(chunkRoot, Integer.numberOfTrailingZeros(chunkWidth));
      processed += chunkCount;
      paddedCount += chunkWidth;
    }

    while (paddedCount < targetWidth) {
      final long remaining = targetWidth - paddedCount;
      final int alignmentDepth =
          paddedCount == 0 ? targetDepth : Long.numberOfTrailingZeros(paddedCount);
      final int remainingDepth = 63 - Long.numberOfLeadingZeros(remaining);
      final int zeroDepth = Math.min(alignmentDepth, remainingDepth);
      accumulator.append(zeroRoot(zeroDepth), zeroDepth);
      paddedCount += 1L << zeroDepth;
    }
    return accumulator.rootAt(targetDepth);
  }

  private Bytes32 hashTile(
      final PackedOneByteTransactions transactions,
      final int firstTransaction,
      final int transactionCount,
      final int tileWidth,
      final int elementDataDepth,
      final boolean progressiveElements,
      final Scratch scratch) {
    final MemorySegment pairs = scratch.pairs();
    final MemorySegment roots = scratch.roots();

    if (progressiveElements || elementDataDepth > 0) {
      final MemorySegment usedPairs = pairs.asSlice(0, (long) transactionCount * PAIR_SIZE);
      usedPairs.fill((byte) 0);
      for (int i = 0; i < transactionCount; i++) {
        usedPairs.set(
            ValueLayout.JAVA_BYTE,
            (long) i * PAIR_SIZE,
            transactions.valueAt(firstTransaction + i));
      }
      hasher.hash64(pairs, roots, transactionCount);
    } else {
      final MemorySegment usedRoots = roots.asSlice(0, (long) transactionCount * ROOT_SIZE);
      usedRoots.fill((byte) 0);
      for (int i = 0; i < transactionCount; i++) {
        usedRoots.set(
            ValueLayout.JAVA_BYTE,
            (long) i * ROOT_SIZE,
            transactions.valueAt(firstTransaction + i));
      }
    }

    if (!progressiveElements) {
      for (int zeroDepth = 1; zeroDepth < elementDataDepth; zeroDepth++) {
        expandWithRightRoot(roots, pairs, transactionCount, zeroRoot(zeroDepth));
        hasher.hash64(pairs, roots, transactionCount);
      }
    }

    expandWithRightRoot(roots, pairs, transactionCount, lengthRoot(1));
    hasher.hash64(pairs, roots, transactionCount);

    if (transactionCount < tileWidth) {
      roots
          .asSlice(
              (long) transactionCount * ROOT_SIZE,
              (long) (tileWidth - transactionCount) * ROOT_SIZE)
          .fill((byte) 0);
    }

    MemorySegment current = roots;
    MemorySegment output = pairs;
    int width = tileWidth;
    while (width > 1) {
      hasher.hash64(current, output, width / 2L);
      final MemorySegment swap = current;
      current = output;
      output = swap;
      width /= 2;
    }
    return Bytes32.wrap(current.asSlice(0, ROOT_SIZE).toArray(ValueLayout.JAVA_BYTE));
  }

  private static void expandWithRightRoot(
      final MemorySegment compactRoots,
      final MemorySegment pairs,
      final int count,
      final Bytes32 rightRoot) {
    final MemorySegment right = MemorySegment.ofArray(rightRoot.toArrayUnsafe());
    for (int i = 0; i < count; i++) {
      MemorySegment.copy(
          compactRoots, (long) i * ROOT_SIZE, pairs, (long) i * PAIR_SIZE, ROOT_SIZE);
      MemorySegment.copy(right, 0, pairs, (long) i * PAIR_SIZE + ROOT_SIZE, ROOT_SIZE);
    }
  }

  private Scratch allocateScratch(final Arena arena) {
    return new Scratch(
        arena.allocate((long) tileSize * PAIR_SIZE, PAIR_SIZE),
        arena.allocate((long) tileSize * ROOT_SIZE, ROOT_SIZE));
  }

  private Bytes32 hashPair(final Bytes32 left, final Bytes32 right) {
    return fallback.wrappedDigest(left, right);
  }

  private static Bytes32 zeroRoot(final int depth) {
    return TreeUtil.ZERO_TREES[depth].hashTreeRoot();
  }

  private static Bytes32 lengthRoot(final int length) {
    return Bytes32.rightPad(Bytes.ofUnsignedLong(length, ByteOrder.LITTLE_ENDIAN));
  }

  private static int ceilingPowerOfTwo(final int value) {
    return value == 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
  }

  private static PackedOneByteTransactions validate(final Bytes bytes, final int count) {
    checkArgument(count > 0, "at least one transaction is required");
    final int dataOffset = Math.multiplyExact(count, Integer.BYTES);
    checkArgument(
        bytes.size() == Math.addExact(dataOffset, count),
        "expected exactly one byte per transaction");
    for (int i = 0; i < count; i++) {
      final int offset =
          bytes.slice(i * Integer.BYTES, Integer.BYTES).toInt(ByteOrder.LITTLE_ENDIAN);
      checkArgument(offset == dataOffset + i, "transaction %s is not one byte", i);
    }
    return new PackedOneByteTransactions(bytes, count, dataOffset);
  }

  private record Scratch(MemorySegment pairs, MemorySegment roots) {}

  private record PackedOneByteTransactions(Bytes bytes, int count, int dataOffset) {
    byte valueAt(final int index) {
      return bytes.get(dataOffset + index);
    }
  }

  private class RootAccumulator {
    private final Bytes32[] roots;

    private RootAccumulator(final int targetDepth) {
      this.roots = new Bytes32[targetDepth + 1];
    }

    private void append(final Bytes32 appendedRoot, final int rootDepth) {
      Bytes32 root = appendedRoot;
      int depth = rootDepth;
      while (roots[depth] != null) {
        root = hashPair(roots[depth], root);
        roots[depth] = null;
        depth++;
      }
      roots[depth] = root;
    }

    private Bytes32 rootAt(final int depth) {
      if (roots[depth] == null) {
        throw new IllegalStateException("No root at depth " + depth);
      }
      for (int i = 0; i < depth; i++) {
        if (roots[i] != null) {
          throw new IllegalStateException("Unmerged root at depth " + i);
        }
      }
      return roots[depth];
    }
  }
}
