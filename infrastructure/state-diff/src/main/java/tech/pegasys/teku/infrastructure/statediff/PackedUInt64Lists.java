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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.schema.AbstractSszProgressiveListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.ListSchemaUtil;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafDataNode;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafNode;
import tech.pegasys.teku.infrastructure.ssz.tree.ProgressiveTreeUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;

/**
 * Converts packed uint64 list trees (balances, inactivity scores) to and from long arrays.
 *
 * <p>A uint64 list packs 4 little-endian values per 32-byte chunk leaf, the last chunk holding only
 * the remaining values. Bounded lists ({@link AbstractSszListSchema}) keep the chunks in a balanced
 * data tree of the schema's depth; progressive lists ({@link AbstractSszProgressiveListSchema}) in
 * a spine of balanced level subtrees. Both are read and built at chunk level, without boxing an
 * element. Any other {@link SszUInt64ListSchema} implementation is rejected.
 */
public final class PackedUInt64Lists {

  private static final int ELEMENT_BYTES = Long.BYTES;
  private static final int ELEMENTS_PER_CHUNK = LeafNode.MAX_BYTE_SIZE / ELEMENT_BYTES;
  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private PackedUInt64Lists() {}

  public static long[] read(final SszUInt64ListSchema<?> schema, final TreeNode listNode) {
    final boolean progressive = isProgressive(schema);
    final long[] values = new long[ListSchemaUtil.getLength(listNode)];
    if (values.length == 0) {
      return values;
    }
    final int chunkCount = chunkCount(values.length);
    final TreeNode dataNode = ListSchemaUtil.getVectorNode(listNode);
    if (progressive) {
      readProgressiveChunks(dataNode, chunkCount, values);
    } else {
      readChunks(dataNode, schema.treeDepth(), 0, chunkCount, values);
    }
    return values;
  }

  public static TreeNode write(final SszUInt64ListSchema<?> schema, final long[] values) {
    final boolean progressive = isProgressive(schema);
    if (values.length > schema.getMaxLength()) {
      throw new IllegalArgumentException(
          values.length + " elements exceed the maximum length " + schema.getMaxLength());
    }
    if (values.length == 0) {
      return schema.getDefaultTree();
    }
    if (progressive) {
      final byte[] packed = new byte[values.length * ELEMENT_BYTES];
      for (int i = 0; i < values.length; i++) {
        LONG_LE.set(packed, i * ELEMENT_BYTES, values[i]);
      }
      return ListSchemaUtil.createPackedProgressiveListTree(Bytes.wrap(packed), values.length);
    }
    final int chunkCount = chunkCount(values.length);
    final List<LeafNode> chunks = new ArrayList<>(chunkCount);
    for (int chunk = 0; chunk < chunkCount; chunk++) {
      final int first = chunk * ELEMENTS_PER_CHUNK;
      final int count = Math.min(ELEMENTS_PER_CHUNK, values.length - first);
      final byte[] data = new byte[count * ELEMENT_BYTES];
      for (int i = 0; i < count; i++) {
        LONG_LE.set(data, i * ELEMENT_BYTES, values[first + i]);
      }
      chunks.add(LeafNode.create(Bytes.wrap(data)));
    }
    return BranchNode.create(
        TreeUtil.createTree(chunks, schema.treeDepth()),
        ListSchemaUtil.toLengthNode(values.length));
  }

  private static boolean isProgressive(final SszUInt64ListSchema<?> schema) {
    if (schema instanceof AbstractSszProgressiveListSchema<?, ?>) {
      return true;
    }
    if (schema instanceof AbstractSszListSchema<?, ?>) {
      return false;
    }
    throw new IllegalArgumentException(
        "Unsupported uint64 list schema " + schema.getClass().getName());
  }

  private static int chunkCount(final int length) {
    return (length + ELEMENTS_PER_CHUNK - 1) / ELEMENTS_PER_CHUNK;
  }

  /** Reads the chunks of a progressive data tree level by level along its spine. */
  private static void readProgressiveChunks(
      final TreeNode dataNode, final int chunkCount, final long[] values) {
    TreeNode spine = dataNode;
    int firstChunk = 0;
    for (int level = 0; firstChunk < chunkCount; level++) {
      if (!(spine instanceof BranchNode branch)) {
        throw new IllegalArgumentException(
            "Progressive list data tree ends at level " + level + " before chunk " + firstChunk);
      }
      final int levelChunks =
          (int) Math.min(ProgressiveTreeUtil.levelCapacity(level), chunkCount - firstChunk);
      readChunks(
          branch.left(), ProgressiveTreeUtil.levelDepth(level), firstChunk, levelChunks, values);
      firstChunk += levelChunks;
      spine = branch.right();
    }
  }

  /**
   * Reads the first {@code count} chunks of the balanced subtree {@code node} of depth {@code
   * depth}, which hold the chunks from {@code firstChunk} on.
   */
  private static void readChunks(
      final TreeNode node,
      final int depth,
      final int firstChunk,
      final int count,
      final long[] values) {
    if (depth == 0) {
      decodeChunk(node, firstChunk, values);
    } else if (node instanceof BranchNode branch) {
      final int leftCount = (int) Math.min(count, 1L << (depth - 1));
      readChunks(branch.left(), depth - 1, firstChunk, leftCount, values);
      if (count > leftCount) {
        readChunks(branch.right(), depth - 1, firstChunk + leftCount, count - leftCount, values);
      }
    } else {
      // A node packing several chunks, such as an SszSuperNode, resolves each chunk itself
      for (int i = 0; i < count; i++) {
        decodeChunk(
            node.get(GIndexUtil.gIdxChildGIndex(GIndexUtil.SELF_G_INDEX, i, depth)),
            firstChunk + i,
            values);
      }
    }
  }

  private static void decodeChunk(final TreeNode node, final int chunk, final long[] values) {
    if (!(node instanceof LeafDataNode leaf) || node instanceof SszSuperNode) {
      throw new IllegalArgumentException(
          "Expected a uint64 chunk leaf for chunk " + chunk + " but found " + node);
    }
    final Bytes data = leaf.getData();
    final int first = chunk * ELEMENTS_PER_CHUNK;
    final int count = Math.min(ELEMENTS_PER_CHUNK, values.length - first);
    if (data.size() < count * ELEMENT_BYTES) {
      throw new IllegalArgumentException(
          "Chunk " + chunk + " holds " + data.size() + " bytes, fewer than " + count + " uint64s");
    }
    final byte[] bytes = data.toArrayUnsafe();
    for (int i = 0; i < count; i++) {
      values[first + i] = (long) LONG_LE.get(bytes, i * ELEMENT_BYTES);
    }
  }
}
