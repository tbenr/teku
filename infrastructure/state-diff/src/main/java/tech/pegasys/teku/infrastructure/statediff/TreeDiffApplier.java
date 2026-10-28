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

package tech.pegasys.teku.infrastructure.statediff;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.schema.SszCollectionSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil.NodeRelation;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszNodeTemplate;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUpdates;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

/**
 * Applies a {@link TreeDiff} produced by {@link TreeDiffer} to a base tree of the same container
 * schema. Applying a diff taken against {@code schema.getDefaultTree()} rebuilds a snapshot.
 *
 * <p>Entries are applied in the order copied, deltas, zeroed, supernodes, leaves. Copy sources are
 * read from the unmodified base tree. Within each section the updates are grouped by tree depth,
 * since {@link TreeUpdates} rejects mixed depths, and applied deepest first.
 */
public final class TreeDiffApplier {

  private final SszContainerSchema<?> schema;
  private final SupernodeSource supernodes;
  private final Map<Integer, SszNodeTemplate> supernodeTemplates = new ConcurrentHashMap<>();

  public TreeDiffApplier(final SszContainerSchema<?> schema, final SupernodeSource supernodes) {
    this.schema = schema;
    this.supernodes = supernodes;
  }

  public TreeNode apply(final TreeNode base, final TreeDiff diff) {
    return apply(base, diff, true);
  }

  /**
   * Applies every section of {@code diff} but its deltas, leaving the delta fields of {@code base}
   * as they are. For a caller folding the deltas of several diffs, see {@link #startChain}.
   */
  public TreeNode applyExcludingDeltas(final TreeNode base, final TreeDiff diff) {
    return apply(base, diff, false);
  }

  /** Replaces the uint64 list field {@code fieldIndex} of {@code tree} by {@code values}. */
  public TreeNode applyDeltaValues(final TreeNode tree, final int fieldIndex, final long[] values) {
    final TreeNode listNode = PackedUInt64Lists.write(uint64ListSchema(fieldIndex), values);
    return tree.updated(schema.getChildGeneralizedIndex(fieldIndex), listNode);
  }

  /** The values of the uint64 list field {@code fieldIndex} of {@code tree}. */
  public long[] readDeltaValues(final TreeNode tree, final int fieldIndex) {
    return PackedUInt64Lists.read(
        uint64ListSchema(fieldIndex), tree.get(schema.getChildGeneralizedIndex(fieldIndex)));
  }

  /**
   * Starts applying a chain of diffs to {@code base}, each diff taken against the result of the
   * previous one. The result equals applying the diffs one by one with {@link #apply}, but delta
   * sections compose: they are folded into one value array per field and each such field is built
   * once, by {@link ChainApplication#finish()}, instead of once per diff.
   */
  public ChainApplication startChain(final TreeNode base) {
    return new ChainApplication(base);
  }

  private TreeNode apply(final TreeNode base, final TreeDiff diff, final boolean includeDeltas) {
    TreeNode tree = base;

    final UpdatesByDepth copied = new UpdatesByDepth();
    for (final CopiedEntry entry : diff.copied()) {
      copied.put(entry.targetGIndex(), base.get(entry.sourceGIndex()));
    }
    tree = copied.applyTo(tree);

    if (includeDeltas) {
      final UpdatesByDepth deltas = new UpdatesByDepth();
      for (final DeltaEntry entry : diff.deltas()) {
        final long[] values =
            UInt64DeltaCodec.apply(
                readDeltaValues(base, entry.fieldIndex()), entry.encodedDeltas());
        deltas.put(
            schema.getChildGeneralizedIndex(entry.fieldIndex()),
            PackedUInt64Lists.write(uint64ListSchema(entry.fieldIndex()), values));
      }
      tree = deltas.applyTo(tree);
    }

    final UpdatesByDepth zeroed = new UpdatesByDepth();
    for (final ZeroedEntry entry : diff.zeroed()) {
      final TreeNode zeroTree = TreeUtil.ZERO_TREES_BY_ROOT.get(entry.zeroRoot());
      if (zeroTree == null) {
        throw new IllegalArgumentException("Not a zero tree root: " + entry.zeroRoot());
      }
      zeroed.put(entry.gIndex(), zeroTree);
    }
    tree = zeroed.applyTo(tree);

    final UpdatesByDepth superNodes = new UpdatesByDepth();
    for (final SupernodeEntry entry : diff.supernodes()) {
      final Bytes ssz =
          supernodes
              .load(entry.hash())
              .orElseThrow(() -> new MissingSupernodeException(entry.hash()));
      superNodes.put(
          entry.gIndex(),
          new SszSuperNode(entry.depth(), supernodeTemplate(entry.fieldIndex()), ssz));
    }
    tree = superNodes.applyTo(tree);

    final UpdatesByDepth leaves = new UpdatesByDepth();
    for (final LeafEntry entry : diff.leaves()) {
      leaves.put(entry.gIndex(), LeafNode.create(entry.data()));
    }
    return leaves.applyTo(tree);
  }

  private SszUInt64ListSchema<?> uint64ListSchema(final int fieldIndex) {
    if (schema.getChildSchema(checkFieldIndex(fieldIndex))
        instanceof SszUInt64ListSchema<?> listSchema) {
      return listSchema;
    }
    throw new IllegalArgumentException("Field " + fieldIndex + " is not a uint64 list");
  }

  private SszNodeTemplate supernodeTemplate(final int fieldIndex) {
    return supernodeTemplates.computeIfAbsent(
        checkFieldIndex(fieldIndex),
        index -> {
          final SszSchema<?> fieldSchema = schema.getChildSchema(index);
          if (!(fieldSchema instanceof SszCollectionSchema<?, ?> collectionSchema)) {
            throw new IllegalArgumentException("Field " + index + " is not a collection");
          }
          return SszNodeTemplate.createFromType(collectionSchema.getElementSchema());
        });
  }

  private int checkFieldIndex(final int fieldIndex) {
    if (fieldIndex < 0 || fieldIndex >= schema.getFieldsCount()) {
      throw new IllegalArgumentException("Invalid field index " + fieldIndex);
    }
    return fieldIndex;
  }

  /**
   * A chain of diffs being applied with folded delta sections; see {@link #startChain}. Not
   * thread-safe.
   *
   * <p>A diff's delta entries are folded into the pending values of their fields and its other
   * sections are applied at once. A diff with another entry addressing a delta field (pending or
   * its own) would observe or overwrite values not yet written, so it is rejected; {@link
   * TreeDiffer} never produces one.
   */
  public final class ChainApplication {
    private final Int2ObjectMap<long[]> pending = new Int2ObjectLinkedOpenHashMap<>();
    private TreeNode tree;
    private int listBuilds;
    private boolean finished;

    private ChainApplication(final TreeNode base) {
      this.tree = base;
    }

    /** Applies the next diff of the chain. */
    public void apply(final TreeDiff diff) {
      checkNotFinished();
      final IntSet fields = new IntOpenHashSet();
      for (final DeltaEntry entry : diff.deltas()) {
        if (!fields.add(entry.fieldIndex())) {
          throw new IllegalArgumentException(
              "Duplicate update at gIndex " + schema.getChildGeneralizedIndex(entry.fieldIndex()));
        }
      }
      checkNoEntryAddressesDeltaFields(diff);
      for (final DeltaEntry entry : diff.deltas()) {
        final int fieldIndex = entry.fieldIndex();
        final long[] current = pending.get(fieldIndex);
        pending.put(
            fieldIndex,
            UInt64DeltaCodec.apply(
                current != null ? current : readDeltaValues(tree, fieldIndex),
                entry.encodedDeltas()));
      }
      tree = applyExcludingDeltas(tree, diff);
    }

    /** Writes the folded delta fields and returns the tree of the last diff applied. */
    public TreeNode finish() {
      checkNotFinished();
      finished = true;
      writePending();
      return tree;
    }

    /** The uint64 lists built so far: one per folded field. */
    public int listBuilds() {
      return listBuilds;
    }

    private void writePending() {
      for (final Int2ObjectMap.Entry<long[]> entry : pending.int2ObjectEntrySet()) {
        tree = applyDeltaValues(tree, entry.getIntKey(), entry.getValue());
        listBuilds++;
      }
      pending.clear();
    }

    private void checkNoEntryAddressesDeltaFields(final TreeDiff diff) {
      final LongList deltaFieldGIndices = new LongArrayList();
      pending.keySet().forEach((final int field) -> deltaFieldGIndices.add(fieldGIndex(field)));
      diff.deltas().forEach(entry -> deltaFieldGIndices.add(fieldGIndex(entry.fieldIndex())));
      if (deltaFieldGIndices.isEmpty()) {
        return;
      }
      final LongList gIndices = new LongArrayList();
      diff.copied()
          .forEach(
              entry -> {
                gIndices.add(entry.sourceGIndex());
                gIndices.add(entry.targetGIndex());
              });
      diff.zeroed().forEach(entry -> gIndices.add(entry.gIndex()));
      diff.supernodes().forEach(entry -> gIndices.add(entry.gIndex()));
      diff.leaves().forEach(entry -> gIndices.add(entry.gIndex()));
      for (int i = 0; i < gIndices.size(); i++) {
        for (int j = 0; j < deltaFieldGIndices.size(); j++) {
          final NodeRelation relation =
              GIndexUtil.gIdxCompare(gIndices.getLong(i), deltaFieldGIndices.getLong(j));
          if (relation != NodeRelation.LEFT && relation != NodeRelation.RIGHT) {
            throw new IllegalArgumentException(
                "Diff entry at gIndex "
                    + gIndices.getLong(i)
                    + " addresses delta field gIndex "
                    + deltaFieldGIndices.getLong(j)
                    + ", which a chain application cannot fold");
          }
        }
      }
    }

    private long fieldGIndex(final int fieldIndex) {
      return schema.getChildGeneralizedIndex(checkFieldIndex(fieldIndex));
    }

    private void checkNotFinished() {
      if (finished) {
        throw new IllegalStateException("Chain application already finished");
      }
    }
  }

  /** Updates keyed by depth, then gIndex, so each depth becomes one ordered {@link TreeUpdates}. */
  private static final class UpdatesByDepth {
    private final NavigableMap<Integer, NavigableMap<Long, TreeNode>> byDepth = new TreeMap<>();

    private void put(final long gIndex, final TreeNode node) {
      final TreeNode previous =
          byDepth
              .computeIfAbsent(GIndexUtil.gIdxGetDepth(gIndex), __ -> new TreeMap<>())
              .put(gIndex, node);
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate update at gIndex " + gIndex);
      }
    }

    private TreeNode applyTo(final TreeNode tree) {
      TreeNode result = tree;
      for (final Map.Entry<Integer, NavigableMap<Long, TreeNode>> depthUpdates :
          byDepth.descendingMap().entrySet()) {
        final NavigableMap<Long, TreeNode> updates = depthUpdates.getValue();
        result = expandNonBranchAncestors(result, updates.keySet(), depthUpdates.getKey());
        result =
            result.updated(
                new TreeUpdates(
                    new ArrayList<>(updates.keySet()), new ArrayList<>(updates.values())));
      }
      return result;
    }

    /**
     * Replaces any non-branch node on the path to one of {@code gIndices} (all at {@code depth})
     * with a zero tree reaching that depth, since {@link TreeUpdates} cannot descend through a
     * leaf. This happens where the target holds a branch over a leaf of the base, such as a list
     * element appended over the zero leaf of an empty list's data tree. The differ emits such a
     * branch with no base to compare against, so its entries cover every position below the leaf
     * and the placeholder zero tree is entirely overwritten once all sections are applied.
     */
    private static TreeNode expandNonBranchAncestors(
        final TreeNode tree, final Iterable<Long> gIndices, final int depth) {
      TreeNode result = tree;
      for (final long gIndex : gIndices) {
        TreeNode node = result;
        for (int ancestorDepth = 0; ancestorDepth < depth; ancestorDepth++) {
          if (node instanceof BranchNode branch) {
            final boolean right = ((gIndex >>> (depth - ancestorDepth - 1)) & 1) == 1;
            node = right ? branch.right() : branch.left();
          } else {
            final int height = depth - ancestorDepth;
            result =
                result.updated(
                    gIndex >>> height,
                    TreeUtil.createDefaultTree(1L << height, LeafNode.EMPTY_LEAF));
            break;
          }
        }
      }
      return result;
    }
  }
}
