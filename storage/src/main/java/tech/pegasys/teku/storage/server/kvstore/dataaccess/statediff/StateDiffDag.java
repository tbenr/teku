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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.DiffLevels;
import tech.pegasys.teku.infrastructure.statediff.MissingSupernodeException;
import tech.pegasys.teku.infrastructure.statediff.SupernodeSource;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.ColumnEntry;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;

/**
 * The state diff DAG: beacon states of checkpoint blocks stored as diffs against coarser ancestor
 * states, down to a snapshot (a diff against the default state tree).
 *
 * <p>Writes go through a {@link StateDiffDagTransaction} bound to one {@link
 * KvStoreCombinedDao.HotUpdater}. Reads follow each node's parent pointer to its snapshot and apply
 * the payloads in order; they never consult the level configuration.
 *
 * <p>The instance holds the per-level diff base cache, so one instance should live as long as the
 * database it writes to. It is meant to be used from the storage thread.
 */
public class StateDiffDag {

  /** Upper bound on any pointer walk, so a corrupted pointer cycle fails instead of looping. */
  static final int MAX_WALK_LENGTH = 1 << 16;

  private static final Comparator<Bytes32> BY_HEX = Comparator.comparing(Bytes32::toHexString);

  private final StateDiffDagConfig config;
  private final DiffLevels levels;
  private final BeaconStateDiffSupport support;
  private final LevelBaseCache baseCache;
  private final StateDiffMetrics metrics;
  private final AtomicLong anchorNodesCreatedLate = new AtomicLong();
  private final AtomicLong anchorsRepaired = new AtomicLong();
  private CommittedNodeReader committedReader;

  public StateDiffDag(
      final Spec spec, final StateDiffDagConfig config, final MetricsSystem metricsSystem) {
    this.config = config;
    this.levels = config.diffLevels();
    this.support = new BeaconStateDiffSupport(spec);
    this.baseCache = new LevelBaseCache(levels.levelCount(), config.baseCacheLevels());
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
   *     corrupt, or the reconstructed state root differs from the recorded one
   * @throws MissingSupernodeException when a referenced supernode is absent
   */
  public Optional<BeaconState> reconstruct(final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    return reconstruct(readerFor(dao), blockRoot);
  }

  /**
   * The roots of the nodes needed to reconstruct {@code blockRoot}: the node itself, then each
   * parent up to and including the snapshot. Empty when there is no node for {@code blockRoot}.
   */
  public List<Bytes32> reconstructionChain(final KvStoreCombinedDao dao, final Bytes32 blockRoot) {
    return loadChain(readerFor(dao), blockRoot).stream()
        .map(ChainLink::root)
        .collect(Collectors.toList());
  }

  /**
   * Checks the stored DAG: recounts the supernode references of every stored node against the
   * stored counts and supernodes, checks that the parent of every node other than a snapshot is
   * stored, and reconstructs the state of the finalized checkpoint. Reads every node, so it is
   * meant for offline use.
   */
  public StateDiffVerificationReport verify(final KvStoreCombinedDao dao) {
    final Map<Bytes32, Long> referencingNodes = new HashMap<>();
    final Map<Bytes32, Bytes32> parentRoots = new HashMap<>();
    final Set<Bytes32> nodeRoots = new HashSet<>();
    final List<Bytes32> corruptNodes = new ArrayList<>();
    try (final Stream<ColumnEntry<Bytes32, Bytes>> nodes = dao.streamStateDiffNodes()) {
      nodes.forEach(
          entry -> {
            nodeRoots.add(entry.getKey());
            final StateDiffNodeRecord record;
            final Set<Bytes32> hashes;
            try {
              record = StateDiffNodeRecordCodec.decode(entry.getValue());
              hashes = new HashSet<>(TreeDiffCodec.supernodeHashes(record.payload()));
            } catch (final IllegalArgumentException e) {
              corruptNodes.add(entry.getKey());
              return;
            }
            if (!record.snapshot()) {
              parentRoots.put(entry.getKey(), record.parentNodeRoot());
            }
            hashes.forEach(hash -> referencingNodes.merge(hash, 1L, Long::sum));
          });
    }
    corruptNodes.sort(BY_HEX);
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
    final List<Bytes32> nodesWithMissingParent =
        parentRoots.entrySet().stream()
            .filter(entry -> !nodeRoots.contains(entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted(BY_HEX)
            .toList();

    final Optional<Bytes32> anchorRoot = dao.getFinalizedCheckpoint().map(Checkpoint::getRoot);
    int anchorChainLength = 0;
    boolean anchorReconstructionOk = false;
    Optional<String> anchorReconstructionError = Optional.empty();
    final long start = System.nanoTime();
    if (anchorRoot.isEmpty()) {
      anchorReconstructionError = Optional.of("No finalized checkpoint");
    } else {
      try {
        anchorChainLength = reconstructionChain(dao, anchorRoot.get()).size();
        if (reconstruct(dao, anchorRoot.get()).isPresent()) {
          anchorReconstructionOk = true;
        } else {
          anchorReconstructionError =
              Optional.of(
                  "No state diff node for the finalized checkpoint root " + anchorRoot.get());
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
        nodesWithMissingParent,
        corruptNodes,
        anchorRoot,
        anchorChainLength,
        anchorReconstructionOk,
        anchorReconstructionMillis,
        anchorReconstructionError);
  }

  public StateDiffDagTransaction begin(
      final KvStoreCombinedDao dao, final KvStoreCombinedDao.HotUpdater updater) {
    return new StateDiffDagTransaction(this, dao, updater);
  }

  /** Counts a hot state stored without the information placing it in the DAG, so not written. */
  public void recordSkippedNoInfo() {
    metrics.onNodeSkippedNoInfo();
  }

  /**
   * Counts a finalized anchor whose node was rewritten as a snapshot because its chain, or the
   * previous anchor's, could not be loaded.
   */
  public void recordAnchorRepaired() {
    anchorsRepaired.incrementAndGet();
    metrics.onAnchorRepaired();
  }

  /** The number of anchors repaired since this instance was created. */
  public long getAnchorsRepaired() {
    return anchorsRepaired.get();
  }

  /**
   * Counts a finalized anchor whose node had to be created on finalization because none was written
   * when its epoch was imported.
   */
  public void recordAnchorCreatedLate() {
    anchorNodesCreatedLate.incrementAndGet();
    metrics.onAnchorCreatedLate();
  }

  /** The number of anchor nodes created on finalization since this instance was created. */
  public long getAnchorNodesCreatedLate() {
    return anchorNodesCreatedLate.get();
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
   * Codec errors in a payload are reported as a {@link StateDiffVerificationException} naming the
   * node, so that one type carries all corruption besides a missing supernode.
   */
  Optional<BeaconState> reconstruct(final NodeReader reader, final Bytes32 blockRoot) {
    try {
      return reconstructChain(reader, blockRoot);
    } catch (final StateDiffVerificationException | MissingSupernodeException e) {
      metrics.onVerificationFailure();
      throw e;
    }
  }

  private Optional<BeaconState> reconstructChain(final NodeReader reader, final Bytes32 blockRoot) {
    final long start = System.nanoTime();
    final List<ChainLink> chain = loadChain(reader, blockRoot);
    if (chain.isEmpty()) {
      return Optional.empty();
    }
    // Every link of a chain has the milestone of its snapshot, since a node whose parent is from
    // another milestone is written as a snapshot; the folded delta fields rely on one schema
    final StateDiffNodeRecord snapshot = chain.getLast().record();
    final TreeDiffApplier.ChainApplication application =
        support
            .applierFor(snapshot.blockSlot(), reader)
            .startChain(support.schemaFor(snapshot.blockSlot()).getDefaultTree());
    for (int i = chain.size() - 1; i >= 0; i--) {
      final StateDiffNodeRecord record = chain.get(i).record();
      if (!support.sameMilestone(record.blockSlot(), snapshot.blockSlot())) {
        throw new StateDiffVerificationException(
            "Node "
                + chain.get(i).root()
                + " at slot "
                + record.blockSlot()
                + " is from another milestone than its snapshot at slot "
                + snapshot.blockSlot()
                + "; chain from node to snapshot: "
                + describe(chain));
      }
      try {
        application.apply(TreeDiffCodec.deserialize(record.payload()));
      } catch (final IllegalArgumentException e) {
        throw corruptPayload(chain.get(i).root(), blockRoot, chain, e);
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
              + "; chain from node to snapshot: "
              + describe(chain));
    }
    metrics.onReconstruct(System.nanoTime() - start, chain.size());
    return Optional.of(state);
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
            + "; chain from node to snapshot: "
            + describe(chain),
        cause);
  }

  /** Loads the chain from {@code blockRoot} to its snapshot, node first. */
  List<ChainLink> loadChain(final NodeReader reader, final Bytes32 blockRoot) {
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

  /**
   * Committed reads go through one reader per DAO instance, so the reader, which is also the
   * supernode source, stays the same object across reconstructions and the applier memoized for it
   * in {@link BeaconStateDiffSupport#applierFor} is reused.
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  private synchronized NodeReader readerFor(final KvStoreCombinedDao dao) {
    if (committedReader == null || committedReader.dao() != dao) {
      committedReader = new CommittedNodeReader(dao);
    }
    return committedReader;
  }

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
  }
}
