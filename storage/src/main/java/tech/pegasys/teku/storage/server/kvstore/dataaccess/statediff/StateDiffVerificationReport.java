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

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * The result of checking the stored state diff DAG: supernode reference counts recounted from the
 * stored nodes, supernode content against its hash, parent pointers, and a reconstruction of the
 * finalized anchor state.
 *
 * @param nodeCount the number of stored nodes
 * @param supernodeCount the number of stored supernodes (their SSZ)
 * @param refCountMismatches supernodes whose stored reference count differs from the number of
 *     stored nodes referencing them, including counts without references and references without a
 *     count
 * @param missingSupernodes supernodes referenced by a stored node whose SSZ is not stored
 * @param unreferencedSupernodes supernodes whose SSZ is stored but that no stored node references
 * @param supernodesHashChecked the number of stored supernodes whose content was hashed and
 *     compared with the hash it is stored under; unreferenced supernodes are not checked, since the
 *     field and depth needed to hash them come from a referencing node
 * @param supernodeHashMismatches checked supernodes whose content does not hash to the hash it is
 *     stored under. Reconstruction trusts a supernode's content to match its hash, so the anchor
 *     reconstruction does not detect these
 * @param nodesWithMissingParent nodes, other than snapshots, whose parent node is not stored
 * @param corruptNodes nodes whose record or payload cannot be decoded; their references and parents
 *     are not counted
 * @param anchorRoot the root of the finalized checkpoint, empty when the database has none
 * @param anchorChainLength the number of nodes in the anchor's reconstruction chain, 0 when the
 *     chain cannot be loaded
 * @param anchorReconstructionOk whether the anchor state was reconstructed and matched its recorded
 *     state root
 * @param anchorReconstructionMillis the time the anchor reconstruction took
 * @param anchorReconstructionError why the anchor could not be reconstructed
 */
public record StateDiffVerificationReport(
    long nodeCount,
    long supernodeCount,
    List<RefCountMismatch> refCountMismatches,
    List<Bytes32> missingSupernodes,
    List<Bytes32> unreferencedSupernodes,
    long supernodesHashChecked,
    List<Bytes32> supernodeHashMismatches,
    List<Bytes32> nodesWithMissingParent,
    List<Bytes32> corruptNodes,
    Optional<Bytes32> anchorRoot,
    int anchorChainLength,
    boolean anchorReconstructionOk,
    long anchorReconstructionMillis,
    Optional<String> anchorReconstructionError) {

  public StateDiffVerificationReport(
      final long nodeCount,
      final long supernodeCount,
      final List<RefCountMismatch> refCountMismatches,
      final List<Bytes32> missingSupernodes,
      final List<Bytes32> unreferencedSupernodes,
      final long supernodesHashChecked,
      final List<Bytes32> supernodeHashMismatches,
      final List<Bytes32> nodesWithMissingParent,
      final List<Bytes32> corruptNodes,
      final Optional<Bytes32> anchorRoot,
      final int anchorChainLength,
      final boolean anchorReconstructionOk,
      final long anchorReconstructionMillis,
      final Optional<String> anchorReconstructionError) {
    this.nodeCount = nodeCount;
    this.supernodeCount = supernodeCount;
    this.refCountMismatches = List.copyOf(refCountMismatches);
    this.missingSupernodes = List.copyOf(missingSupernodes);
    this.unreferencedSupernodes = List.copyOf(unreferencedSupernodes);
    this.supernodesHashChecked = supernodesHashChecked;
    this.supernodeHashMismatches = List.copyOf(supernodeHashMismatches);
    this.nodesWithMissingParent = List.copyOf(nodesWithMissingParent);
    this.corruptNodes = List.copyOf(corruptNodes);
    this.anchorRoot = anchorRoot;
    this.anchorChainLength = anchorChainLength;
    this.anchorReconstructionOk = anchorReconstructionOk;
    this.anchorReconstructionMillis = anchorReconstructionMillis;
    this.anchorReconstructionError = anchorReconstructionError;
  }

  /** Whether any check failed. */
  public boolean hasProblems() {
    return !refCountMismatches.isEmpty()
        || !missingSupernodes.isEmpty()
        || !unreferencedSupernodes.isEmpty()
        || !supernodeHashMismatches.isEmpty()
        || !nodesWithMissingParent.isEmpty()
        || !corruptNodes.isEmpty()
        || !anchorReconstructionOk;
  }

  /**
   * A supernode whose stored reference count is not the number of stored nodes referencing it.
   *
   * @param referencingNodes the number of stored nodes referencing the supernode
   * @param storedCount the stored count, empty when there is none
   */
  public record RefCountMismatch(
      Bytes32 hash, long referencingNodes, Optional<UInt64> storedCount) {}
}
