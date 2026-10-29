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

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafDataNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszNodeTemplate;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.PatchEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

/**
 * Computes the {@link TreeDiff} between two trees of the same container schema by comparing cached
 * hashes along changed paths. Nothing is serialized: both trees are expected to be hashed already.
 *
 * <p>Per container field: a delta field (a packed uint64 list) is emitted as element deltas; a
 * field whose new subtree equals the base subtree of another field of the same schema is emitted as
 * a copy (list rotation); anything else is walked. The walk emits a zeroed entry for a subtree that
 * is the zero tree, a patch or a supernode reference for an {@link SszSuperNode}, the data of any
 * other leaf, and recurses into branches.
 *
 * <p>A changed supernode whose base is a non-empty supernode of the same depth, and which has at
 * least as many elements, is compared element by element and emitted as a patch of the changed and
 * appended elements when that takes fewer bytes than a whole reference: the reference's 32-byte
 * hash plus the supernode's content, which the reference adds to the supernode store. Otherwise,
 * and always for a snapshot (its base, a default tree, holds no non-empty supernode), the supernode
 * is emitted as a whole reference and its bytes handed to the supernode sink.
 *
 * <p>Equal roots do not imply equal trees. A leaf's root is its data zero-padded to 32 bytes and a
 * supernode's root ignores trailing zero elements, so appending a zero element to a packed list or
 * resetting a list to zeros can leave a data subtree's root unchanged while the leaf data lengths
 * the schema reads and serializes do change. A subtree is therefore skipped outright only when the
 * base and target hold the very same node instance, which is the common case for state trees
 * derived from one another. A pair with equal roots but distinct instances is walked without
 * hashing and only leaves whose data length differs are emitted, so the applier reproduces the
 * target's exact leaf data rather than just its root.
 *
 * <p>A {@link FrozenSuperNode} is compared like an {@link SszSuperNode} by root, depth and size,
 * all known without loading it; its content is loaded only when it changed, to build a patch over
 * it, and when a whole reference to it is emitted only if the sink asks for the content.
 */
public final class TreeDiffer {

  private final SszContainerSchema<?> schema;
  private final IntSet deltaFieldIndices;
  private final long[] fieldGIndices;
  private final int[][] copySourceCandidates;

  public TreeDiffer(final SszContainerSchema<?> schema, final IntSet deltaFieldIndices) {
    this.schema = schema;
    this.deltaFieldIndices = IntSets.unmodifiable(new IntOpenHashSet(deltaFieldIndices));
    final int fieldCount = schema.getFieldsCount();
    this.deltaFieldIndices.forEach(
        (final int fieldIndex) -> {
          if (fieldIndex < 0
              || fieldIndex >= fieldCount
              || !(schema.getChildSchema(fieldIndex) instanceof SszUInt64ListSchema<?>)) {
            throw new IllegalArgumentException(
                "Delta field " + fieldIndex + " is not a uint64 list field of " + schema);
          }
        });
    this.fieldGIndices = new long[fieldCount];
    this.copySourceCandidates = new int[fieldCount][];
    for (int i = 0; i < fieldCount; i++) {
      fieldGIndices[i] = schema.getChildGeneralizedIndex(i);
      copySourceCandidates[i] = sameSchemaFields(i);
    }
  }

  /**
   * Fields that may serve as copy sources for field {@code fieldIndex}. Only fields of an equal
   * schema qualify, so a copied subtree always has the leaf layout the target field expects. Delta
   * fields never do: a chain application folds their values and cannot serve them as a copy source.
   */
  private int[] sameSchemaFields(final int fieldIndex) {
    final SszSchema<?> fieldSchema = schema.getChildSchema(fieldIndex);
    return IntStream.range(0, schema.getFieldsCount())
        .filter(
            j ->
                j != fieldIndex
                    && !deltaFieldIndices.contains(j)
                    && schema.getChildSchema(j).equals(fieldSchema))
        .toArray();
  }

  /**
   * Receives the supernodes a diff references whole: the hash and a supplier of the content, which
   * a frozen supernode loads only when called.
   */
  @FunctionalInterface
  public interface SupernodeSink {
    void accept(Bytes32 hash, Supplier<Bytes> content);
  }

  public TreeDiff diff(
      final TreeNode base, final TreeNode target, final BiConsumer<Bytes32, Bytes> sink) {
    return diffLazy(base, target, (hash, content) -> sink.accept(hash, content.get()));
  }

  /**
   * As {@link #diff}, handing the content of each whole reference to {@code sink} as a supplier, so
   * that a frozen supernode whose content the sink does not need is never loaded.
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  public TreeDiff diffLazy(final TreeNode base, final TreeNode target, final SupernodeSink sink) {
    final Collector collector = new Collector(sink);
    for (int fieldIndex = 0; fieldIndex < fieldGIndices.length; fieldIndex++) {
      final long fieldGIndex = fieldGIndices[fieldIndex];
      final TreeNode baseNode = base.get(fieldGIndex);
      final TreeNode targetNode = target.get(fieldGIndex);
      if (baseNode == targetNode) {
        continue;
      }
      final Bytes32 targetRoot = targetNode.hashTreeRoot();
      final boolean sameRoot = baseNode.hashTreeRoot().equals(targetRoot);
      if (deltaFieldIndices.contains(fieldIndex)) {
        // Equal roots mean equal values, and the applier rebuilds delta fields from values alone
        if (!sameRoot) {
          collector.deltas.add(deltaEntry(fieldIndex, baseNode, targetNode));
        }
        continue;
      }
      if (!sameRoot) {
        final int copySource = findCopySource(base, fieldIndex, targetNode, targetRoot);
        if (copySource >= 0) {
          collector.copied.add(new CopiedEntry(fieldGIndex, fieldGIndices[copySource]));
          continue;
        }
      }
      collector.diffSubtree(fieldIndex, fieldGIndex, baseNode, targetNode);
    }
    return collector.toTreeDiff();
  }

  private DeltaEntry deltaEntry(
      final int fieldIndex, final TreeNode baseNode, final TreeNode targetNode) {
    final SszUInt64ListSchema<?> listSchema =
        (SszUInt64ListSchema<?>) schema.getChildSchema(fieldIndex);
    return new DeltaEntry(
        fieldIndex,
        UInt64DeltaCodec.encode(
            PackedUInt64Lists.read(listSchema, baseNode),
            PackedUInt64Lists.read(listSchema, targetNode)));
  }

  private int findCopySource(
      final TreeNode base,
      final int fieldIndex,
      final TreeNode targetNode,
      final Bytes32 targetRoot) {
    for (final int candidate : copySourceCandidates[fieldIndex]) {
      final TreeNode candidateNode = base.get(fieldGIndices[candidate]);
      if (candidateNode.hashTreeRoot().equals(targetRoot)
          && sameLeafData(candidateNode, targetNode)) {
        return candidate;
      }
    }
    return -1;
  }

  /**
   * Whether two trees already known to share a root also hold leaves of the same data length, and
   * hence the same data. Shared instances answer immediately.
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  private static boolean sameLeafData(final TreeNode a, final TreeNode b) {
    if (a == b) {
      return true;
    }
    if (isSupernode(a)) {
      return isSupernode(b)
          && supernodeDepth(a) == supernodeDepth(b)
          && supernodeSize(a) == supernodeSize(b);
    }
    if (a instanceof LeafDataNode leafA) {
      return b instanceof LeafDataNode leafB
          && !isSupernode(b)
          && leafA.getData().size() == leafB.getData().size();
    }
    return a instanceof BranchNode branchA
        && b instanceof BranchNode branchB
        && sameLeafData(branchA.left(), branchB.left())
        && ((hasSharedChildren(branchA) && hasSharedChildren(branchB))
            || sameLeafData(branchA.right(), branchB.right()));
  }

  /**
   * Whether {@code node} carries exactly the data of the canonical zero tree {@code zeroTree}: same
   * shape down to leaves without data. A subtree holding zero-valued data leaves (a list reset to
   * zeros) hashes the same but must not be replaced by data-less leaves, so it is walked instead.
   * An empty {@link SszSuperNode} only pads a list beyond its length and counts as zero.
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  private static boolean isZeroTree(final TreeNode node, final TreeNode zeroTree) {
    if (node == zeroTree) {
      return true;
    }
    if (isSupernode(node)) {
      return supernodeSize(node) == 0;
    }
    if (zeroTree instanceof BranchNode zeroBranch) {
      return node instanceof BranchNode branch
          && isZeroTree(branch.left(), zeroBranch.left())
          && ((hasSharedChildren(branch) && hasSharedChildren(zeroBranch))
              || isZeroTree(branch.right(), zeroBranch.right()));
    }
    return node instanceof LeafDataNode leaf && leaf.getData().isEmpty();
  }

  /** Whether {@code node} is an {@link SszSuperNode} or a {@link FrozenSuperNode}. */
  static boolean isSupernode(final TreeNode node) {
    return node instanceof SszSuperNode || node instanceof FrozenSuperNode;
  }

  private static int supernodeDepth(final TreeNode node) {
    return node instanceof FrozenSuperNode frozen
        ? frozen.getDepth()
        : ((SszSuperNode) node).getDepth();
  }

  /** The content size of a supernode, without loading a frozen one. */
  private static int supernodeSize(final TreeNode node) {
    return node instanceof FrozenSuperNode frozen
        ? frozen.getSszSize()
        : ((SszSuperNode) node).getData().size();
  }

  /** The supernode {@code node} is, loading a frozen one; {@code null} for any other node. */
  static SszSuperNode asSupernode(final TreeNode node) {
    if (node instanceof SszSuperNode superNode) {
      return superNode;
    }
    return node instanceof FrozenSuperNode frozen ? frozen.load() : null;
  }

  /**
   * Whether both sides of {@code branch} are one node instance. Zero trees and other trees built by
   * sharing (list padding, default vectors) are, and checking such a side once instead of twice
   * keeps the walks over them linear in depth rather than in leaf count.
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  private static boolean hasSharedChildren(final BranchNode branch) {
    return branch.left() == branch.right();
  }

  /**
   * The patch turning {@code baseNode} into {@code target}, the nodes at {@code gIndex} of field
   * {@code fieldIndex}, or {@code null} when a whole reference to {@code target} is the cheaper
   * entry or a patch cannot express the change (no base supernode, another depth, an empty or a
   * longer base).
   */
  private PatchEntry patch(
      final int fieldIndex, final long gIndex, final TreeNode baseNode, final TreeNode targetNode) {
    if (baseNode == null || !isSupernode(baseNode)) {
      return null;
    }
    final Optional<SszNodeTemplate> template =
        TreeDiffApplier.supernodeTemplate(schema, fieldIndex);
    // Checked on depths and sizes first, so a frozen node is not loaded for nothing
    if (supernodeDepth(baseNode) != supernodeDepth(targetNode)
        || supernodeDepth(targetNode) > PatchEntry.MAX_DEPTH
        || supernodeSize(baseNode) == 0
        || supernodeSize(targetNode) < supernodeSize(baseNode)
        || template.isEmpty()) {
      return null;
    }
    final SszSuperNode target = asSupernode(targetNode);
    final Bytes baseData = asSupernode(baseNode).getData();
    final Bytes targetData = target.getData();
    final int elementSize = template.get().getSszLength();
    if (baseData.size() % elementSize != 0 || targetData.size() % elementSize != 0) {
      return null;
    }
    final int baseCount = baseData.size() / elementSize;
    final int targetCount = targetData.size() / elementSize;
    // Both entries carry a gIndex and a depth; a reference adds its hash and the content it stores
    final long referenceBytes = (long) Bytes32.SIZE + targetData.size();
    long patchBytes = TreeDiffCodec.patchEntryBytes(elementSize, targetCount);
    final byte[] baseBytes = baseData.toArrayUnsafe();
    final byte[] targetBytes = targetData.toArrayUnsafe();
    final IntList written = new IntArrayList();
    int previous = -1;
    for (int i = 0; i < targetCount; i++) {
      final int from = i * elementSize;
      final int to = from + elementSize;
      if (i < baseCount && Arrays.equals(baseBytes, from, to, targetBytes, from, to)) {
        continue;
      }
      patchBytes += TreeDiffCodec.patchElementBytes(i - previous - 1, elementSize);
      if (patchBytes >= referenceBytes) {
        return null;
      }
      written.add(i);
      previous = i;
    }
    final byte[] elements = new byte[written.size() * elementSize];
    for (int i = 0; i < written.size(); i++) {
      System.arraycopy(
          targetBytes, written.getInt(i) * elementSize, elements, i * elementSize, elementSize);
    }
    return new PatchEntry(
        fieldIndex,
        gIndex,
        target.getDepth(),
        targetCount,
        elementSize,
        written,
        Bytes.wrap(elements));
  }

  private final class Collector {
    private final SupernodeSink sink;
    private final List<LeafEntry> leaves = new ArrayList<>();
    private final List<SupernodeEntry> supernodes = new ArrayList<>();
    private final List<PatchEntry> patches = new ArrayList<>();
    private final List<ZeroedEntry> zeroed = new ArrayList<>();
    private final List<CopiedEntry> copied = new ArrayList<>();
    private final List<DeltaEntry> deltas = new ArrayList<>();

    private Collector(final SupernodeSink sink) {
      this.sink = sink;
    }

    /**
     * Emits the entries that turn {@code baseNode} into {@code targetNode} at {@code gIndex}, where
     * the two are distinct instances. {@code baseNode} is {@code null} when the base has no
     * matching branch at this position, in which case the whole target subtree is emitted.
     */
    private void diffSubtree(
        final int fieldIndex,
        final long gIndex,
        final TreeNode baseNode,
        final TreeNode targetNode) {
      final Bytes32 targetRoot = targetNode.hashTreeRoot();
      final boolean sameRoot = baseNode != null && baseNode.hashTreeRoot().equals(targetRoot);
      final TreeNode zeroTree = TreeUtil.ZERO_TREES_BY_ROOT.get(targetRoot);
      if (zeroTree != null && isZeroTree(targetNode, zeroTree)) {
        if (!sameRoot || !isZeroTree(baseNode, zeroTree)) {
          zeroed.add(new ZeroedEntry(gIndex, targetRoot));
        }
      } else if (isSupernode(targetNode)) {
        if (!sameRoot || !sameLeafData(baseNode, targetNode)) {
          final PatchEntry patch = patch(fieldIndex, gIndex, baseNode, targetNode);
          if (patch != null) {
            patches.add(patch);
          } else {
            supernodes.add(
                new SupernodeEntry(fieldIndex, gIndex, supernodeDepth(targetNode), targetRoot));
            sink.accept(targetRoot, () -> asSupernode(targetNode).getData());
          }
        }
      } else if (targetNode instanceof LeafDataNode leaf) {
        if (!sameRoot || !sameLeafData(baseNode, targetNode)) {
          leaves.add(new LeafEntry(gIndex, leaf.getData()));
        }
      } else if (targetNode instanceof BranchNode targetBranch) {
        final BranchNode baseBranch = baseNode instanceof BranchNode branch ? branch : null;
        diffChild(
            fieldIndex,
            GIndexUtil.gIdxLeftGIndex(gIndex),
            baseBranch == null ? null : baseBranch.left(),
            targetBranch.left());
        diffChild(
            fieldIndex,
            GIndexUtil.gIdxRightGIndex(gIndex),
            baseBranch == null ? null : baseBranch.right(),
            targetBranch.right());
      } else {
        throw new IllegalArgumentException(
            "Unsupported tree node type " + targetNode.getClass().getName() + " at " + gIndex);
      }
    }

    @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
    private void diffChild(
        final int fieldIndex, final long gIndex, final TreeNode baseChild, final TreeNode target) {
      if (baseChild != target) {
        diffSubtree(fieldIndex, gIndex, baseChild, target);
      }
    }

    private TreeDiff toTreeDiff() {
      return new TreeDiff(leaves, supernodes, patches, zeroed, copied, deltas);
    }
  }
}
