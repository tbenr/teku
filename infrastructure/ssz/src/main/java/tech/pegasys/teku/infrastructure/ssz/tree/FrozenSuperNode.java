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

package tech.pegasys.teku.infrastructure.ssz.tree;

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.jetbrains.annotations.NotNull;
import tech.pegasys.teku.infrastructure.crypto.Sha256;

/**
 * An {@link SszSuperNode} whose content is not held in memory: only its hash tree root, depth,
 * element template and element count are kept, and the packed SSZ of its elements is read from a
 * loader when needed. Meant for supernodes whose content is stored elsewhere under their hash (the
 * state diff supernode column) and is rarely read, such as the validators of a fully withdrawn
 * registry range.
 *
 * <p>The root is returned without loading. Reading a descendant or the data loads the content and
 * delegates to a transient {@link SszSuperNode}, memoized through a {@link SoftReference} so that
 * repeated reads within an epoch transition do not reach the store each time while the garbage
 * collector can still drop it. Any update loads the content and returns the plain {@link
 * SszSuperNode} {@link SszSuperNode#updated} builds, so a write always thaws the node. {@link
 * #iterate} visits this node as one node, as {@link SszSuperNode} does.
 *
 * <p>Exploration only (state diff tier 3 attempt, 2026-10-29): the global load and thaw counters
 * and the load call-site recorder (system property {@value #RECORD_LOAD_SITES_PROPERTY}, the number
 * of distinct call-site stacks to keep) exist to measure which code paths still read frozen nodes.
 */
public final class FrozenSuperNode implements TreeNode, LeafDataNode {

  public static final String RECORD_LOAD_SITES_PROPERTY = "teku.frozenSuperNode.recordLoadSites";

  private static final int RECORDED_FRAMES = 8;
  private static final String SSZ_PACKAGE = "tech.pegasys.teku.infrastructure.ssz.";
  private static final LongAdder LOADS = new LongAdder();
  private static final LongAdder MEMO_HITS = new LongAdder();
  private static final LongAdder THAWS = new LongAdder();
  private static final Map<String, LongAdder> LOAD_SITES = new ConcurrentHashMap<>();
  private static volatile int recordLoadSites = Integer.getInteger(RECORD_LOAD_SITES_PROPERTY, 0);
  private static volatile boolean anyCreated = false;

  private final int depth;
  private final SszNodeTemplate elementTemplate;
  private final int elementCount;
  private final Bytes32 hashTreeRoot;
  private final Supplier<Bytes> loader;
  private volatile SoftReference<SszSuperNode> loaded = new SoftReference<>(null);

  /**
   * @param depth the depth of the supernode
   * @param elementTemplate the template of its elements
   * @param elementCount the number of elements its content holds
   * @param hashTreeRoot its hash tree root, trusted, not checked
   * @param loader returns the packed SSZ of its {@code elementCount} elements; throws when the
   *     content cannot be read
   */
  public FrozenSuperNode(
      final int depth,
      final SszNodeTemplate elementTemplate,
      final int elementCount,
      final Bytes32 hashTreeRoot,
      final Supplier<Bytes> loader) {
    checkArgument(depth >= 0 && depth < 31, "Invalid depth %s", depth);
    checkArgument(
        elementCount >= 0 && elementCount <= (1 << depth),
        "Invalid element count %s for depth %s",
        elementCount,
        depth);
    this.depth = depth;
    this.elementTemplate = elementTemplate;
    this.elementCount = elementCount;
    this.hashTreeRoot = hashTreeRoot;
    this.loader = loader;
    markCreated();
  }

  private static void markCreated() {
    if (!anyCreated) {
      anyCreated = true;
    }
  }

  /** Whether any frozen supernode was ever created in this process, for cheap no-op guards. */
  public static boolean anyCreated() {
    return anyCreated;
  }

  public int getDepth() {
    return depth;
  }

  public int getElementCount() {
    return elementCount;
  }

  public SszNodeTemplate getElementTemplate() {
    return elementTemplate;
  }

  /** The size of the content in bytes, known without loading it. */
  public int getSszSize() {
    return elementCount * elementTemplate.getSszLength();
  }

  @Override
  public Bytes32 hashTreeRoot() {
    return hashTreeRoot;
  }

  @Override
  public Bytes32 hashTreeRoot(final Sha256 sha256) {
    return hashTreeRoot;
  }

  @NotNull
  @Override
  public TreeNode get(final long generalizedIndex) {
    if (GIndexUtil.gIdxIsSelf(generalizedIndex)) {
      return this;
    }
    return load().get(generalizedIndex);
  }

  @Override
  public boolean iterate(
      final long thisGeneralizedIndex,
      final long startGeneralizedIndex,
      final TreeVisitor visitor) {
    if (GIndexUtil.gIdxCompare(thisGeneralizedIndex, startGeneralizedIndex)
        == GIndexUtil.NodeRelation.LEFT) {
      return true;
    } else {
      return visitor.visit(this, thisGeneralizedIndex);
    }
  }

  @Override
  public TreeNode updated(final TreeUpdates newNodes) {
    if (newNodes.isEmpty()) {
      return this;
    }
    if (newNodes.isFinal()) {
      return newNodes.getNode(0);
    }
    THAWS.increment();
    return load().updated(newNodes);
  }

  /** Loads the content. Counted as a load even when the memoized node serves it. */
  @Override
  public Bytes getData() {
    return load().getData();
  }

  /**
   * The content as a plain supernode with this node's root, loaded or memoized. Counted as a load.
   */
  public SszSuperNode load() {
    LOADS.increment();
    recordLoadSite();
    final SszSuperNode memoized = loaded.get();
    if (memoized != null) {
      MEMO_HITS.increment();
      return memoized;
    }
    final Bytes ssz = loader.get();
    checkArgument(
        ssz.size() == getSszSize(),
        "Frozen supernode %s loaded %s bytes, expected %s",
        hashTreeRoot,
        ssz.size(),
        getSszSize());
    final SszSuperNode node =
        SszSuperNode.createWithKnownRoot(depth, elementTemplate, ssz, hashTreeRoot);
    loaded = new SoftReference<>(node);
    return node;
  }

  /** Drops the memoized content, so the next read loads again. For measurements. */
  public void dropMemo() {
    loaded = new SoftReference<>(null);
  }

  private static void recordLoadSite() {
    final int limit = recordLoadSites;
    if (limit <= 0) {
      return;
    }
    final String site =
        StackWalker.getInstance()
            .walk(
                frames ->
                    frames
                        // The SSZ tree and view internals and the JDK only say how the read
                        // was reached, not by which caller
                        .filter(
                            frame ->
                                !frame.getClassName().startsWith(SSZ_PACKAGE)
                                    && !frame.getClassName().startsWith("java."))
                        .limit(RECORDED_FRAMES)
                        .map(StackWalker.StackFrame::toString)
                        .collect(Collectors.joining("\n    at ", "    at ", "")));
    final LongAdder counter = LOAD_SITES.get(site);
    if (counter != null) {
      counter.increment();
    } else if (LOAD_SITES.size() < limit) {
      LOAD_SITES.computeIfAbsent(site, __ -> new LongAdder()).increment();
    }
  }

  /** Loads since start or the last {@link #resetCounters()}, memo hits included. */
  public static long loads() {
    return LOADS.sum();
  }

  /** Loads served by the memoized content, without calling the loader. */
  public static long memoHits() {
    return MEMO_HITS.sum();
  }

  /** Updates that thawed a frozen node into a plain {@link SszSuperNode}. */
  public static long thaws() {
    return THAWS.sum();
  }

  /** The recorded load call-site stacks with their load counts, most loads first. */
  public static Map<String, Long> loadSites() {
    return LOAD_SITES.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().sum(),
                (a, b) -> a,
                LinkedHashMap::new));
  }

  /** Sets the number of distinct load call-site stacks recorded; 0 disables recording. */
  public static void setRecordLoadSites(final int limit) {
    recordLoadSites = limit;
  }

  public static void resetCounters() {
    Stream.of(LOADS, MEMO_HITS, THAWS).forEach(LongAdder::reset);
    LOAD_SITES.clear();
  }

  @Override
  public String toString() {
    return "FrozenSuperNode{depth="
        + depth
        + ", elements="
        + elementCount
        + ", root="
        + hashTreeRoot
        + "}";
  }
}
