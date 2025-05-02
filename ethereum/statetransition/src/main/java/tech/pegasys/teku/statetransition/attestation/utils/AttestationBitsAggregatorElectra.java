/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.statetransition.attestation.utils;

import com.google.common.base.MoreObjects;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.BitSet;
import java.util.Objects;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitvector;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBitlistSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBitvectorSchema;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationSchema;

/**
 * Optimized version using java.util.BitSet internally for core operations. Converts back to SSZ
 * types lazily on demand.
 */
class AttestationBitsAggregatorElectra implements AttestationBitsAggregator {

  // Schemas needed for final SSZ conversion
  private final SszBitlistSchema<?> aggregationBitsSchema;
  private final SszBitvectorSchema<?> committeeBitsSchema;

  // Internal representation for performance
  private BitSet internalAggregationBits;
  private BitSet internalCommitteeBits;

  // Derived state, calculated from internal BitSets and committeesSize
  private Int2IntMap committeeBitsStartingPositions;
  private final Int2IntMap committeesSize; // Assume effectively immutable

  // Cache for lazy SSZ conversion.
  private SszBitlist cachedAggregationBits = null;
  private SszBitvector cachedCommitteeBits = null;
  private int cachedAggregationBitlistSize = -1; // Cache the calculated size too

  AttestationBitsAggregatorElectra(
      final SszBitlist initialAggregationBits,
      final SszBitvector initialCommitteeBits,
      final Int2IntMap committeesSize) {
    this.aggregationBitsSchema = initialAggregationBits.getSchema();
    this.committeeBitsSchema = initialCommitteeBits.getSchema();
    this.committeesSize = Objects.requireNonNull(committeesSize, "committeesSize cannot be null");

    // One-time conversion from SSZ to internal BitSet
    // Assumes an efficient way exists, otherwise implement helpers (see below)
    this.internalAggregationBits = initialAggregationBits.getAsBitSet();
    this.internalCommitteeBits = initialCommitteeBits.getAsBitSet();

    // Calculate initial starting positions based on internal BitSet
    this.committeeBitsStartingPositions =
        calculateCommitteeStartingPositions(this.internalCommitteeBits, this.committeesSize);
  }

  // Static factory method
  static AttestationBitsAggregator fromAttestationSchema(
      final AttestationSchema<?> attestationSchema, final Int2IntMap committeesSize) {
    SszBitlist emptyAggregationBits = attestationSchema.createEmptyAggregationBits();
    SszBitvector emptyCommitteeBits =
        attestationSchema
            .createEmptyCommitteeBits()
            .orElseThrow(
                () -> new IllegalStateException("Electra schema must provide committee bits"));
    return new AttestationBitsAggregatorElectra(
        emptyAggregationBits, emptyCommitteeBits, committeesSize);
  }

  @Override
  public void or(final AttestationBitsAggregator other) {
    // Convert other's SSZ to BitSet for processing
    BitSet otherInternalCommitteeBits = other.getCommitteeBits().getAsBitSet();
    BitSet otherInternalAggregationBits = other.getAggregationBits().getAsBitSet();

    // Perform the OR operation using internal BitSets
    orInternal(otherInternalCommitteeBits, otherInternalAggregationBits, false);
  }

  @Override
  public boolean aggregateWith(final Attestation other) {
    // Convert other's SSZ to BitSet for processing
    BitSet otherInternalCommitteeBits = other.getCommitteeBitsRequired().getAsBitSet();
    BitSet otherInternalAggregationBits = other.getAggregationBits().getAsBitSet();

    // Perform the OR operation using internal BitSets
    return orInternal(otherInternalCommitteeBits, otherInternalAggregationBits, true);
  }

  @Override
  public void or(final Attestation other) {
    // Convert other's SSZ to BitSet for processing
    BitSet otherInternalCommitteeBits = other.getCommitteeBitsRequired().getAsBitSet();
    BitSet otherInternalAggregationBits = other.getAggregationBits().getAsBitSet();

    // Check if the other is a single non-aggregating attestation
    if (otherInternalCommitteeBits.cardinality() == 1
        && otherInternalAggregationBits.cardinality() == 1) {
      // Extract the single committee and aggregation bit
      int otherCommitteeBit = otherInternalCommitteeBits.nextSetBit(0);
      int otherAggregationBit = otherInternalAggregationBits.nextSetBit(0);

      if (internalCommitteeBits.get(otherCommitteeBit)) {
        // Fill up the internal aggregation bits for this committee
        singleNonAggregatingFillUp(otherCommitteeBit, otherAggregationBit);
        return;
      }
    }

    // Perform the OR operation using internal BitSets
    orInternal(otherInternalCommitteeBits, otherInternalAggregationBits, false);
  }

  private void singleNonAggregatingFillUp(
      final int otherCommitteeBit, final int otherAggregationBit) {

    final int thisStartingPosition = committeeBitsStartingPositions.get(otherCommitteeBit);
    this.internalAggregationBits.set(thisStartingPosition + otherAggregationBit);

    invalidateCache(); // State changed
  }

  /**
   * Internal OR logic, operating directly on BitSet objects. Modifies the internal state of this
   * aggregator.
   */
  private boolean orInternal(
      final BitSet otherCommitteeBits,
      final BitSet otherAggregationBits,
      final boolean isAggregation) {

    // Basic comparison using BitSet.equals() is efficient
    if (otherCommitteeBits.equals(this.internalCommitteeBits)) {
      // Faster path: Committee bits are the same
      final BitSet currentAggBitsClone = (BitSet) this.internalAggregationBits.clone();
      currentAggBitsClone.or(otherAggregationBits); // Modify the clone

      if (isAggregation) {
        // Check intersection on original bits before modification
        if (this.internalAggregationBits.intersects(otherAggregationBits)) {
          return false; // Cannot aggregate if bits intersect
        }
      }
      // Update internal state only if successful
      this.internalAggregationBits = currentAggBitsClone;
      invalidateCache(); // State changed
      return true;
    }

    // --- Full merge required ---
    // Clone current state to attempt merge; only update if successful
    final BitSet potentialCommitteeBits = (BitSet) this.internalCommitteeBits.clone();
    potentialCommitteeBits.or(otherCommitteeBits);

    // Recalculate starting positions based on the potential combined committee bits
    // Note: This recalculation happens *before* we know if aggregation is valid.
    final Int2IntMap otherCommitteeBitsStartingPositions =
        calculateCommitteeStartingPositions(otherCommitteeBits, this.committeesSize);
    final Int2IntMap aggregatedCommitteeBitsStartingPositions =
        calculateCommitteeStartingPositions(potentialCommitteeBits, this.committeesSize);

    // Determine the required size for the new combined aggregation bits
    int combinedAggregationBitsSize =
        calculateCombinedAggregationBitsetSize(
            potentialCommitteeBits, aggregatedCommitteeBitsStartingPositions);
    if (combinedAggregationBitsSize < 0) { // Handles empty committee case
      this.internalCommitteeBits = potentialCommitteeBits;
      this.internalAggregationBits = new BitSet(); // Empty
      this.committeeBitsStartingPositions = aggregatedCommitteeBitsStartingPositions;
      invalidateCache();
      return true;
    }

    // Use BitSet for efficient construction
    final BitSet potentialAggregationBits = new BitSet(combinedAggregationBitsSize);

    // Calculate current starting positions based on *current* internal state
    // Needed for accessing *this* aggregator's bits correctly.
    final Int2IntMap currentCommitteeBitsStartingPositions = this.committeeBitsStartingPositions;

    // Iterate over committees in the potential combined result
    for (int committeeIndex = potentialCommitteeBits.nextSetBit(0);
        committeeIndex >= 0;
        committeeIndex = potentialCommitteeBits.nextSetBit(committeeIndex + 1)) {
      final int committeeSize = this.committeesSize.get(committeeIndex);
      final int destinationStart = aggregatedCommitteeBitsStartingPositions.get(committeeIndex);

      // Check presence in current and other BitSets
      final boolean inThis = this.internalCommitteeBits.get(committeeIndex);
      final boolean inOther = otherCommitteeBits.get(committeeIndex);

      final BitSet source1Bits;
      final int source1Start;
      final BitSet source2Bits;
      final int source2Start;

      if (inThis && inOther) {
        source1Bits = this.internalAggregationBits;
        source1Start = currentCommitteeBitsStartingPositions.get(committeeIndex);
        source2Bits = otherAggregationBits;
        source2Start = otherCommitteeBitsStartingPositions.get(committeeIndex);
      } else if (inThis) {
        source1Bits = this.internalAggregationBits;
        source1Start = currentCommitteeBitsStartingPositions.get(committeeIndex);
        source2Bits = null;
        source2Start = 0;
      } else { // Only inOther
        source1Bits = otherAggregationBits;
        source1Start = otherCommitteeBitsStartingPositions.get(committeeIndex);
        source2Bits = null;
        source2Start = 0;
      }

      // Iterate positions within the committee
      for (int pos = 0; pos < committeeSize; pos++) {
        final boolean bit1 = source1Bits.get(source1Start + pos);
        boolean resultBit = bit1;

        if (source2Bits != null) {
          final boolean bit2 = source2Bits.get(source2Start + pos);
          if (isAggregation && bit1 && bit2) {
            return false;
          }
          resultBit = bit1 || bit2;
        }

        if (resultBit) {
          potentialAggregationBits.set(destinationStart + pos);
        }
      }
    }

    // --- Success: Update internal state ---
    this.internalCommitteeBits = potentialCommitteeBits;
    this.internalAggregationBits = potentialAggregationBits;
    this.committeeBitsStartingPositions =
        aggregatedCommitteeBitsStartingPositions; // Update cached positions
    invalidateCache(); // State changed

    return true;
  }

  // Calculate starting positions based on internal BitSet
  private static Int2IntMap calculateCommitteeStartingPositions(
      final BitSet committeeBits, final Int2IntMap committeesSizeMap) {
    final Int2IntMap positions = new Int2IntOpenHashMap();
    int currentOffset = 0;
    for (int i = committeeBits.nextSetBit(0); i >= 0; i = committeeBits.nextSetBit(i + 1)) {
      positions.put(i, currentOffset);
      currentOffset += committeesSizeMap.getOrDefault(i, 0);
    }
    return positions;
  }

  // Calculate required size for the combined aggregation bitlist
  private int calculateCombinedAggregationBitsetSize(
      final BitSet combinedCommitteeBits, final Int2IntMap startingPositions) {
    int lastCommitteeIndex =
        combinedCommitteeBits.length() - 1; // BitSet.length() gives index of highest set bit + 1
    if (lastCommitteeIndex < 0) { // Handle empty BitSet
      return -1;
    }

    final int lastCommitteeStartingPosition = startingPositions.get(lastCommitteeIndex);
    return lastCommitteeStartingPosition + this.committeesSize.get(lastCommitteeIndex);
  }

  // Helper to clear cached SSZ objects when internal state changes
  private void invalidateCache() {
    this.cachedAggregationBits = null;
    this.cachedCommitteeBits = null;
    this.cachedAggregationBitlistSize = -1;
  }

  @Override
  public boolean isSuperSetOf(
      final Attestation other) { // Convert other's SSZ to BitSet for processing
    BitSet otherInternalCommitteeBits = other.getCommitteeBitsRequired().getAsBitSet();
    BitSet otherInternalAggregationBits = other.getAggregationBits().getAsBitSet();

    // Fast path check using BitSet.equals()
    if (this.internalCommitteeBits.equals(otherInternalCommitteeBits)) {
      // Check if otherAggregationBits is a subset of internalAggregationBits
      BitSet intersection = (BitSet) this.internalAggregationBits.clone();
      intersection.and(otherInternalAggregationBits);
      return intersection.equals(otherInternalAggregationBits);
    }

    // Check committee coverage: other's committees must be a subset of this's
    BitSet committeeIntersection = (BitSet) this.internalCommitteeBits.clone();
    committeeIntersection.and(otherInternalCommitteeBits);
    if (!committeeIntersection.equals(otherInternalCommitteeBits)) {
      return false; // Not all of other's committees are present in this
    }

    // Pre-calculate starting positions for 'other' based on its committee bits
    Int2IntMap otherCommitteeBitsStartingPositions =
        calculateCommitteeStartingPositions(otherInternalCommitteeBits, this.committeesSize);

    // Iterate only through committees present in 'other'
    for (int committeeIndex = otherInternalCommitteeBits.nextSetBit(0);
        committeeIndex >= 0;
        committeeIndex = otherInternalCommitteeBits.nextSetBit(committeeIndex + 1)) {
      final int committeeSize = this.committeesSize.get(committeeIndex);
      // Get start positions in both this aggregator and the 'other' attestation
      final int thisStartingPosition = this.committeeBitsStartingPositions.get(committeeIndex);
      final int otherStartingPosition = otherCommitteeBitsStartingPositions.get(committeeIndex);

      // Check all bits within this committee range
      for (int pos = 0; pos < committeeSize; pos++) {
        if (otherInternalAggregationBits.get(otherStartingPosition + pos)) {
          // If bit is set in other, it must be set in this
          if (!this.internalAggregationBits.get(thisStartingPosition + pos)) {
            return false; // Bit set in other is not set here -> not a superset
          }
        }
      }
    }

    return true; // All checks passed
  }

  // --- Getters with Lazy Conversion and Caching ---

  @Override
  public SszBitlist getAggregationBits() {
    if (cachedAggregationBits == null) {
      // Calculate the required size for the SSZ bitlist based on current committees
      // This needs to be recalculated whenever committeeBits change.
      if (cachedAggregationBitlistSize < 0) {
        cachedAggregationBitlistSize =
            calculateCombinedAggregationBitsetSize(
                this.internalCommitteeBits, this.committeeBitsStartingPositions);
        if (cachedAggregationBitlistSize < 0) {
          cachedAggregationBitlistSize = 0; // Handle empty case for schema wrap
        }
      }
      // Convert internal BitSet back to SSZ Bitlist using the schema
      // Assumes wrapBitSet is efficient.
      cachedAggregationBits =
          aggregationBitsSchema.wrapBitSet(
              cachedAggregationBitlistSize, this.internalAggregationBits);
    }
    return cachedAggregationBits;
  }

  @Override
  public SszBitvector getCommitteeBits() {
    if (cachedCommitteeBits == null) {
      // Convert internal BitSet back to SSZ Bitvector using the schema
      // Assumes createFromBitSet exists or wrapBitSet works for vectors too (check schema API)
      // Using wrapBitSet as a placeholder if createFromBitSet isn't available
      // Vector size is fixed by the schema, not dynamic like bitlist size.
      cachedCommitteeBits =
          committeeBitsSchema.wrapBitSet(
              committeeBitsSchema.getLength(), this.internalCommitteeBits);
      // Alternatively, if a specific method exists:
      // cachedCommitteeBits = committeeBitsSchema.createFromBitSet(this.internalCommitteeBits);
    }
    return cachedCommitteeBits;
  }

  // --- Other Methods ---

  @Override
  public Int2IntMap getCommitteesSize() {
    return committeesSize;
  }

  @Override
  public boolean requiresCommitteeBits() {
    return true;
  }

  @Override
  public String toString() {
    // Show internal state for debugging if needed
    return MoreObjects.toStringHelper(this)
        .add("internalAggregationBits", internalAggregationBits.cardinality() + " bits set")
        .add("internalCommitteeBits", internalCommitteeBits.cardinality() + " bits set")
        .add("committeesSize", committeesSize.size() + " entries")
        .add("committeeBitsStartingPositions", committeeBitsStartingPositions.size() + " entries")
        .add("cached", cachedAggregationBits != null || cachedCommitteeBits != null)
        .toString();
  }
}
