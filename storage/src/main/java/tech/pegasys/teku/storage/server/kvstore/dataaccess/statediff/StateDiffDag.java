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

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.DiffLevels;
import tech.pegasys.teku.infrastructure.statediff.MissingSupernodeException;
import tech.pegasys.teku.infrastructure.statediff.SupernodeSource;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.ColumnEntry;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationReport.CorruptNode;

/**
 * The state diff DAG: beacon states of checkpoint blocks stored as diffs against coarser ancestor
 * states, down to a snapshot (a diff against the default state tree).
 *
 * <p>Writes go through a {@link StateDiffDagTransaction} bound to one {@link
 * KvStoreCombinedDao.HotUpdater}. Reads follow each node's parent pointer to its snapshot, or to
 * the nearest ancestor whose state is in the base cache, and apply the payloads above it in order;
 * they never consult the level configuration.
 *
 * <p>The instance holds the per-level diff base cache, so one instance should live as long as the
 * database it writes to. It is meant to be used from the storage thread.
 */
public class StateDiffDag {

  private static final Logger LOG = LogManager.getLogger();

  /** Upper bound on any pointer walk, so a corrupted pointer cycle fails instead of looping. */
  static final int MAX_WALK_LENGTH = 1 << 16;

  private static final Comparator<Bytes32> BY_HEX = Comparator.comparing(Bytes32::toHexString);

  /** The number of supernodes {@link #verify} loads in one batch to check their content. */
  private static final int VERIFY_SUPERNODE_BATCH_SIZE = 1024;

  private static final long RECONSTRUCTION_FAILURE_LOG_INTERVAL_MILLIS = 60_000;

  private static final Function<Bytes32, Optional<TreeNode>> NO_KNOWN_TREES =
      root -> Optional.empty();

  private final StateDiffDagConfig config;
  private final DiffLevels levels;
  private final BeaconStateDiffSupport support;
  private final LevelBaseCache baseCache;
  private final StateDiffMetrics metrics;
  private final AtomicLong lastReconstructionFailureLogMillis = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong suppressedReconstructionFailureLogs = new AtomicLong();

  public StateDiffDag(
      final Spec spec, final StateDiffDagConfig config, final MetricsSystem metricsSystem) {
    this.config = config;
    this.levels = config.diffLevels();
    this.support = new BeaconStateDiffSupport(spec);
    this.baseCache = new LevelBaseCache(levels.levelCount());
    this.metrics = new StateDiffMetrics(metricsSystem);
  }

  public StateDiffDagConfig getConfig() {
    return config;
  }

  /**
   * Reconstructs the state of the node stored for {@code blockRoot}.
   *
   * @return empty when there is no node for {@code blockRoot}
   * @throws StateDiffVerificationException when the chain is broken, a node record or payload is
   *     corrupt, a referenced supernode is absent, or the reconstructed state root differs from the
   *     recorded one
   */
  public Optional<BeaconState> reconstruct(final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    return reconstruct(new CommittedNodeReader(dao), blockRoot);
  }

  /**
   * Reconstructs the state of the node stored for {@code blockRoot} as {@link
   * #reconstruct(KvStoreCombinedDao, Bytes32)} does, then puts it in the base cache at the node's
   * level: the diff base of the next nodes written above it and the start of the reconstructions
   * whose chain passes through it. For the finalized anchor when the store is loaded.
   */
  public Optional<BeaconState> reconstructAndCache(
      final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    return reconstructNode(new CommittedNodeReader(dao), blockRoot, NO_KNOWN_TREES)
        .map(
            node -> {
              baseCache.put(LevelBaseCache.cacheLevel(node.record()), blockRoot, node.state());
              return node.state();
            });
  }

  /**
   * Reconstructs the state of the node stored for {@code blockRoot}, reporting a node that cannot
   * be reconstructed as absent. The failure is logged at ERROR at most once a minute, with the
   * count of the failures not logged since.
   *
   * @return empty when there is no node for {@code blockRoot} or it cannot be reconstructed
   */
  public Optional<BeaconState> reconstructOrEmpty(
      final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    try {
      return reconstruct(dao, blockRoot);
    } catch (final StateDiffVerificationException e) {
      logReconstructionFailure(blockRoot, e);
      return Optional.empty();
    }
  }

  /** Logs a reconstruction failure at most once per interval, counting the others. */
  private void logReconstructionFailure(
      final Bytes32 blockRoot, final StateDiffVerificationException e) {
    final long now = System.currentTimeMillis();
    final long last = lastReconstructionFailureLogMillis.get();
    final boolean due =
        last == Long.MIN_VALUE || now - last >= RECONSTRUCTION_FAILURE_LOG_INTERVAL_MILLIS;
    if (!due || !lastReconstructionFailureLogMillis.compareAndSet(last, now)) {
      suppressedReconstructionFailureLogs.incrementAndGet();
      return;
    }
    LOG.error(
        "Cannot reconstruct the state of block {} from state diff storage, regenerating it by"
            + " replaying blocks instead ({} similar errors not logged since the last one)",
        blockRoot,
        suppressedReconstructionFailureLogs.getAndSet(0),
        e);
  }

  /**
   * The roots of the nodes needed to reconstruct {@code blockRoot}: the node itself, then each
   * parent up to and including the snapshot. Empty when there is no node for {@code blockRoot}.
   */
  public List<Bytes32> reconstructionChain(final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    return loadChain(new CommittedNodeReader(dao), blockRoot).stream()
        .map(ChainLink::root)
        .collect(Collectors.toList());
  }

  /**
   * Checks the stored DAG: recounts the supernode references of every stored node against the
   * stored counts and supernodes, checks that the content of every referenced supernode hashes to
   * the hash it is stored under, checks that the parent of every node other than a snapshot is
   * stored, and reconstructs the state of the finalized checkpoint. Reads every node and supernode,
   * so it is meant for offline use.
   */
  public StateDiffVerificationReport verify(final KvStoreCombinedDao dao) {
    final Map<Bytes32, Long> referencingNodes = new HashMap<>();
    // One referencing entry per hash, with the slot of its node: what hashing the content needs
    final Map<Bytes32, SupernodeReference> supernodeReferences = new HashMap<>();
    final Map<Bytes32, Bytes32> parentRoots = new HashMap<>();
    final Set<Bytes32> nodeRoots = new HashSet<>();
    final List<CorruptNode> corruptNodes = new ArrayList<>();
    try (final Stream<ColumnEntry<Bytes32, Bytes>> nodes = dao.streamStateDiffNodes()) {
      nodes.forEach(
          entry -> {
            nodeRoots.add(entry.getKey());
            final StateDiffNodeRecord record;
            final List<SupernodeEntry> supernodes;
            try {
              record = StateDiffNodeRecordCodec.decode(entry.getValue());
              supernodes = TreeDiffCodec.supernodeEntries(record.payload());
            } catch (final IllegalArgumentException e) {
              corruptNodes.add(new CorruptNode(entry.getKey(), e.getMessage()));
              return;
            }
            if (!record.snapshot()) {
              parentRoots.put(entry.getKey(), record.parentNodeRoot());
            }
            final Set<Bytes32> hashes = new HashSet<>();
            for (final SupernodeEntry supernode : supernodes) {
              if (hashes.add(supernode.hash())) {
                referencingNodes.merge(supernode.hash(), 1L, Long::sum);
                supernodeReferences.putIfAbsent(
                    supernode.hash(), new SupernodeReference(record.blockSlot(), supernode));
              }
            }
          });
    }
    corruptNodes.sort(Comparator.comparing(CorruptNode::root, BY_HEX));
    final Map<Bytes32, UInt64> storedCounts = new HashMap<>();
    try (final Stream<ColumnEntry<Bytes32, UInt64>> counts = dao.streamStateSupernodeRefCounts()) {
      counts.forEach(entry -> storedCounts.put(entry.getKey(), entry.getValue()));
    }
    final Set<Bytes32> storedSupernodes;
    try (final Stream<Bytes32> hashes = dao.streamStateSupernodeHashes()) {
      storedSupernodes = hashes.collect(Collectors.toSet());
    }

    final Set<Bytes32> countedHashes = new HashSet<>(referencingNodes.keySet());
    countedHashes.addAll(storedCounts.keySet());
    final List<StateDiffVerificationReport.RefCountMismatch> refCountMismatches =
        countedHashes.stream()
            .sorted(BY_HEX)
            .flatMap(
                hash -> {
                  final long expected = referencingNodes.getOrDefault(hash, 0L);
                  final Optional<UInt64> stored = Optional.ofNullable(storedCounts.get(hash));
                  // A stored count is never zero: the supernode is deleted instead
                  final boolean matches =
                      stored
                          .map(count -> count.longValue() == expected && expected > 0)
                          .orElse(false);
                  return matches
                      ? Stream.empty()
                      : Stream.of(
                          new StateDiffVerificationReport.RefCountMismatch(hash, expected, stored));
                })
            .toList();
    final List<Bytes32> missingSupernodes =
        referencingNodes.keySet().stream()
            .filter(hash -> !storedSupernodes.contains(hash))
            .sorted(BY_HEX)
            .toList();
    final List<Bytes32> unreferencedSupernodes =
        storedSupernodes.stream()
            .filter(hash -> !referencingNodes.containsKey(hash))
            .sorted(BY_HEX)
            .toList();
    final List<Bytes32> hashChecked =
        storedSupernodes.stream().filter(supernodeReferences::containsKey).sorted(BY_HEX).toList();
    final List<Bytes32> supernodeHashMismatches =
        findSupernodeHashMismatches(dao, hashChecked, supernodeReferences);
    final List<Bytes32> nodesWithMissingParent =
        parentRoots.entrySet().stream()
            .filter(entry -> !nodeRoots.contains(entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted(BY_HEX)
            .toList();

    final Optional<Bytes32> anchorRoot = dao.getFinalizedCheckpoint().map(Checkpoint::getRoot);
    int anchorChainLength = 0;
    Optional<String> anchorReconstructionError = Optional.empty();
    final long start = System.nanoTime();
    if (anchorRoot.isEmpty()) {
      anchorReconstructionError = Optional.of("No finalized checkpoint");
    } else {
      try {
        // Loaded once down to the snapshot and applied whole, whatever the base cache holds
        final NodeReader reader = new CommittedNodeReader(dao);
        final List<ChainLink> chain = loadChain(reader, anchorRoot.get());
        anchorChainLength = chain.size();
        if (chain.isEmpty()) {
          anchorReconstructionError =
              Optional.of(
                  "No state diff node for the finalized checkpoint root " + anchorRoot.get());
        } else {
          try {
            applyChain(reader, anchorRoot.get(), chain, Optional.empty(), start);
          } catch (final StateDiffVerificationException e) {
            metrics.onVerificationFailure();
            throw e;
          }
        }
      } catch (final RuntimeException e) {
        anchorReconstructionError = Optional.of(e.toString());
      }
    }
    final long anchorReconstructionMillis = (System.nanoTime() - start) / 1_000_000;

    return new StateDiffVerificationReport(
        nodeRoots.size(),
        storedSupernodes.size(),
        refCountMismatches,
        missingSupernodes,
        unreferencedSupernodes,
        hashChecked.size(),
        supernodeHashMismatches,
        nodesWithMissingParent,
        corruptNodes,
        anchorRoot,
        anchorChainLength,
        anchorReconstructionMillis,
        anchorReconstructionError);
  }

  /**
   * The supernodes among {@code hashes} whose stored content does not hash to the hash it is stored
   * under, or cannot be the content of the supernode referenced, in the order of {@code hashes}.
   */
  private List<Bytes32> findSupernodeHashMismatches(
      final KvStoreCombinedDao dao,
      final List<Bytes32> hashes,
      final Map<Bytes32, SupernodeReference> references) {
    final List<Bytes32> mismatches = new ArrayList<>();
    for (int start = 0; start < hashes.size(); start += VERIFY_SUPERNODE_BATCH_SIZE) {
      final List<Bytes32> batch =
          hashes.subList(start, Math.min(start + VERIFY_SUPERNODE_BATCH_SIZE, hashes.size()));
      final Map<Bytes32, Bytes> contents = dao.getStateSupernodes(batch);
      for (final Bytes32 hash : batch) {
        final Bytes content = contents.get(hash);
        final SupernodeReference reference = references.get(hash);
        if (content != null && !contentMatches(reference, content)) {
          mismatches.add(hash);
        }
      }
    }
    return mismatches;
  }

  private boolean contentMatches(final SupernodeReference reference, final Bytes content) {
    try {
      return support
          .applierFor(reference.blockSlot())
          .computeSupernodeRoot(reference.entry(), content)
          .equals(reference.entry().hash());
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  public StateDiffDagTransaction begin(
      final KvStoreCombinedDao dao, final KvStoreCombinedDao.HotUpdater updater) {
    return new StateDiffDagTransaction(this, dao, updater);
  }

  /** Publishes the window start of a database opened with one, which transactions then update. */
  public void recordWindowStart(final UInt64 epoch) {
    metrics.onWindowStart(epoch);
  }

  DiffLevels levels() {
    return levels;
  }

  BeaconStateDiffSupport support() {
    return support;
  }

  LevelBaseCache baseCache() {
    return baseCache;
  }

  StateDiffMetrics metrics() {
    return metrics;
  }

  /**
   * Reconstructs the state of {@code blockRoot}, counting any failure as a verification failure.
   * Codec errors in a node record or payload and a missing supernode are reported as a {@link
   * StateDiffVerificationException} naming the node, so that one type carries every reason the DAG
   * cannot serve a state.
   */
  Optional<BeaconState> reconstruct(final NodeReader reader, final Bytes32 blockRoot) {
    return reconstruct(reader, blockRoot, NO_KNOWN_TREES);
  }

  /**
   * Reconstructs the state of {@code blockRoot} as {@link #reconstruct(NodeReader, Bytes32)} does,
   * also starting from a state tree of {@code knownTrees}, such as one written in the running
   * transaction, when its node is in the chain.
   */
  Optional<BeaconState> reconstruct(
      final NodeReader reader,
      final Bytes32 blockRoot,
      final Function<Bytes32, Optional<TreeNode>> knownTrees) {
    return reconstructNode(reader, blockRoot, knownTrees).map(ReconstructedNode::state);
  }

  private Optional<ReconstructedNode> reconstructNode(
      final NodeReader reader,
      final Bytes32 blockRoot,
      final Function<Bytes32, Optional<TreeNode>> knownTrees) {
    try {
      return reconstructChain(reader, blockRoot, knownTrees);
    } catch (final StateDiffVerificationException e) {
      metrics.onVerificationFailure();
      throw e;
    }
  }

  /**
   * Loads the chain of {@code blockRoot} down to its snapshot or to the first link below the node
   * whose state tree is known, from {@code knownTrees} or the base cache, and applies the payloads
   * above it. The node's own payload is always applied, so a read never skips the record it asks
   * for. The state root of the result is checked against the node's record either way.
   */
  private Optional<ReconstructedNode> reconstructChain(
      final NodeReader reader,
      final Bytes32 blockRoot,
      final Function<Bytes32, Optional<TreeNode>> knownTrees) {
    final long start = System.nanoTime();
    // Set by the link that ends the chain when its state tree is known
    final AtomicReference<TreeNode> knownStartTree = new AtomicReference<>();
    final List<ChainLink> chain =
        loadChain(
            reader,
            blockRoot,
            link -> {
              final Optional<TreeNode> tree =
                  knownTrees.apply(link.root()).or(() -> baseCache.find(link.root()));
              // A cached tree of another state than the record's never starts a chain
              final boolean usable =
                  tree.map(known -> known.hashTreeRoot().equals(link.record().stateRoot()))
                      .orElse(false);
              if (usable) {
                knownStartTree.set(tree.get());
              }
              return usable;
            });
    if (chain.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        applyChain(reader, blockRoot, chain, Optional.ofNullable(knownStartTree.get()), start));
  }

  /**
   * Applies the payloads of {@code chain}, the loaded chain of {@code blockRoot} (node first), on
   * {@code startTree}, the state tree of its last link, or when empty on the default tree of the
   * last link, its snapshot. Checks the state root of the result against the node's record.
   *
   * @param startNanos when the reconstruction started, for the reconstruction time metric
   */
  private ReconstructedNode applyChain(
      final NodeReader reader,
      final Bytes32 blockRoot,
      final List<ChainLink> chain,
      final Optional<TreeNode> startTree,
      final long startNanos) {
    // Every link of a chain has the milestone of its snapshot, since a node whose parent is from
    // another milestone is written as a snapshot; the folded delta fields rely on one schema
    final ChainLink last = chain.getLast();
    final int appliedLinks = startTree.isPresent() ? chain.size() - 1 : chain.size();
    final StateDiffNodeRecord bottom = last.record();
    final TreeDiffApplier.ChainApplication application =
        support
            .applierFor(bottom.blockSlot())
            .startChain(
                startTree.orElseGet(() -> support.schemaFor(bottom.blockSlot()).getDefaultTree()),
                reader);
    for (int i = appliedLinks - 1; i >= 0; i--) {
      final StateDiffNodeRecord record = chain.get(i).record();
      if (!support.sameMilestone(record.blockSlot(), bottom.blockSlot())) {
        throw new StateDiffVerificationException(
            "Node "
                + chain.get(i).root()
                + " at slot "
                + record.blockSlot()
                + " is from another milestone than node "
                + last.root()
                + " at slot "
                + bottom.blockSlot()
                + " below it; chain from node to snapshot or cached node: "
                + describe(chain));
      }
      try {
        application.apply(TreeDiffCodec.deserialize(record.payload()));
      } catch (final IllegalArgumentException e) {
        throw corruptPayload(chain.get(i).root(), blockRoot, chain, e);
      } catch (final MissingSupernodeException e) {
        throw new StateDiffVerificationException(
            "Missing supernode "
                + e.getHash()
                + " referenced by node "
                + chain.get(i).root()
                + " in the reconstruction chain of node "
                + blockRoot
                + "; chain from node to snapshot or cached node: "
                + describe(chain),
            e);
      }
    }
    final TreeNode tree;
    try {
      tree = application.finish();
    } catch (final IllegalArgumentException e) {
      throw corruptPayload(blockRoot, blockRoot, chain, e);
    }
    metrics.onReconstructListBuilds(application.listBuilds());
    final StateDiffNodeRecord head = chain.getFirst().record();
    final BeaconState state = support.schemaFor(head.blockSlot()).createFromBackingNode(tree);
    final Bytes32 actualRoot = state.hashTreeRoot();
    if (!actualRoot.equals(head.stateRoot())) {
      throw new StateDiffVerificationException(
          "Reconstructed state of node "
              + blockRoot
              + " has root "
              + actualRoot
              + " but the node records "
              + head.stateRoot()
              + "; chain from node to snapshot or cached node: "
              + describe(chain));
    }
    metrics.onReconstruct(System.nanoTime() - startNanos, appliedLinks);
    return new ReconstructedNode(head, state);
  }

  private static StateDiffVerificationException corruptPayload(
      final Bytes32 nodeRoot,
      final Bytes32 blockRoot,
      final List<ChainLink> chain,
      final IllegalArgumentException cause) {
    return new StateDiffVerificationException(
        "Corrupt payload of node "
            + nodeRoot
            + " in the reconstruction chain of node "
            + blockRoot
            + ": "
            + cause.getMessage()
            + "; chain from node to snapshot or cached node: "
            + describe(chain),
        cause);
  }

  /** Loads the chain from {@code blockRoot} to its snapshot, node first. */
  List<ChainLink> loadChain(final NodeReader reader, final Bytes32 blockRoot) {
    return loadChain(reader, blockRoot, link -> false);
  }

  /**
   * Loads the chain from {@code blockRoot} towards its snapshot, node first, ending at the snapshot
   * or at the first link after the node that {@code isLast} accepts.
   */
  private List<ChainLink> loadChain(
      final NodeReader reader, final Bytes32 blockRoot, final Predicate<ChainLink> isLast) {
    final Optional<StateDiffNodeRecord> head = reader.getNode(blockRoot);
    if (head.isEmpty()) {
      return List.of();
    }
    final List<ChainLink> chain = new ArrayList<>();
    ChainLink link = new ChainLink(blockRoot, head.get());
    chain.add(link);
    while (!link.record().snapshot()) {
      if (chain.size() >= MAX_WALK_LENGTH) {
        throw new StateDiffVerificationException(
            "Reconstruction chain of node " + blockRoot + " exceeds " + MAX_WALK_LENGTH + " nodes");
      }
      final Bytes32 parentRoot = link.record().parentNodeRoot();
      final Optional<StateDiffNodeRecord> parent = reader.getNode(parentRoot);
      if (parent.isEmpty()) {
        throw new StateDiffVerificationException(
            "Missing parent node "
                + parentRoot
                + " in the reconstruction chain of node "
                + blockRoot
                + ": "
                + describe(chain));
      }
      link = new ChainLink(parentRoot, parent.get());
      chain.add(link);
      if (isLast.test(link)) {
        break;
      }
    }
    return chain;
  }

  /**
   * Loads as much of the chain from {@code blockRoot} towards its snapshot as can be read: stops
   * before a missing or undecodable node instead of failing. For repairs only.
   */
  List<ChainLink> loadChainPrefix(final NodeReader reader, final Bytes32 blockRoot) {
    final List<ChainLink> chain = new ArrayList<>();
    Bytes32 root = blockRoot;
    while (chain.size() < MAX_WALK_LENGTH) {
      final Optional<StateDiffNodeRecord> record;
      try {
        record = reader.getNode(root);
      } catch (final StateDiffVerificationException e) {
        break;
      }
      if (record.isEmpty()) {
        break;
      }
      chain.add(new ChainLink(root, record.get()));
      if (record.get().snapshot()) {
        break;
      }
      root = record.get().parentNodeRoot();
    }
    return chain;
  }

  private static String describe(final List<ChainLink> chain) {
    return chain.stream()
        .map(link -> link.root().toHexString())
        .collect(Collectors.joining(" -> ", "[", "]"));
  }

  record ChainLink(Bytes32 root, StateDiffNodeRecord record) {}

  private record ReconstructedNode(StateDiffNodeRecord record, BeaconState state) {}

  /** A supernode entry of a stored node, with the slot of that node's block. */
  private record SupernodeReference(UInt64 blockSlot, SupernodeEntry entry) {}

  /**
   * Read access to nodes and supernodes, either committed or as seen inside a transaction. A reader
   * is the {@link SupernodeSource} of the reconstructions it serves.
   */
  interface NodeReader extends SupernodeSource {

    Optional<Bytes> getEncodedNode(Bytes32 blockRoot);

    /**
     * Reads and decodes the node of {@code blockRoot}.
     *
     * @throws StateDiffVerificationException when the stored record cannot be decoded
     */
    default Optional<StateDiffNodeRecord> getNode(final Bytes32 blockRoot) {
      return getEncodedNode(blockRoot).map(encoded -> decode(blockRoot, encoded));
    }

    static StateDiffNodeRecord decode(final Bytes32 blockRoot, final Bytes encoded) {
      try {
        return StateDiffNodeRecordCodec.decode(encoded);
      } catch (final IllegalArgumentException e) {
        throw new StateDiffVerificationException(
            "Corrupt state diff node record " + blockRoot + ": " + e.getMessage(), e);
      }
    }
  }

  private record CommittedNodeReader(KvStoreCombinedDao dao) implements NodeReader {

    @Override
    public Optional<Bytes> getEncodedNode(final Bytes32 blockRoot) {
      return dao.getStateDiffNode(blockRoot);
    }

    @Override
    public Optional<Bytes> load(final Bytes32 hash) {
      return dao.getStateSupernode(hash);
    }

    @Override
    public Map<Bytes32, Bytes> loadAll(final Collection<Bytes32> hashes) {
      return dao.getStateSupernodes(hashes);
    }
  }
}
