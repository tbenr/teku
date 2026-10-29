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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.statediff.SupernodeFreezer;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;

/**
 * Freezes the validator supernodes of a reconstructed state whose validators are all withdrawn: a
 * supernode is replaced by a {@link FrozenSuperNode} loading its content from the supernode column
 * when, for every validator in it, {@code effective_balance == 0}, {@code activation_epoch} is set,
 * {@code exit_epoch < epoch} and {@code withdrawable_epoch <= epoch}, where {@code epoch} is the
 * epoch of the node being reconstructed. The two epoch conditions make every frozen validator
 * inactive in the previous, current and next epoch of any state at or after that epoch, and
 * withdrawable in it, which the consensus fast paths rely on (decisions log, tier 3 attempt).
 *
 * <p>The epoch a supernode becomes eligible at, or never, is memoized by hash in a small bounded
 * map, so each distinct supernode is scanned once.
 *
 * <p>Exploration only (state diff tier 3 attempt, 2026-10-29).
 */
public class ExitedValidatorFreezer {

  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final long NEVER = Long.MAX_VALUE;
  private static final int MEMO_SIZE = 1 << 15;

  private static final int ELEMENT_SIZE = Validator.SSZ_SCHEMA.getSszFixedPartSize();
  private static final int EFFECTIVE_BALANCE_OFFSET = fieldOffset(2);
  private static final int ACTIVATION_EPOCH_OFFSET = fieldOffset(5);
  private static final int EXIT_EPOCH_OFFSET = fieldOffset(6);
  private static final int WITHDRAWABLE_EPOCH_OFFSET = fieldOffset(7);

  private final Spec spec;
  private final Map<Bytes32, Long> eligibleFromEpoch =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Bytes32, Long> eldest) {
              return size() > MEMO_SIZE;
            }
          });
  private final LongAdder frozen = new LongAdder();
  private final LongAdder frozenValidators = new LongAdder();

  public ExitedValidatorFreezer(final Spec spec) {
    this.spec = spec;
  }

  /** The offset of field {@code fieldIndex} in the fixed-size SSZ of a validator. */
  private static int fieldOffset(final int fieldIndex) {
    int offset = 0;
    for (int i = 0; i < fieldIndex; i++) {
      offset += Validator.SSZ_SCHEMA.getChildSchema(i).getSszFixedPartSize();
    }
    return offset;
  }

  /**
   * The freezer for the reconstruction of the node at {@code slot}, freezing eligible validator
   * supernodes whose content {@code loader} reloads by hash.
   */
  public SupernodeFreezer forSlot(final UInt64 slot, final Function<Bytes32, Bytes> loader) {
    final long epoch = spec.computeEpochAtSlot(slot).longValue();
    final int validatorsField =
        spec.atSlot(slot)
            .getSchemaDefinitions()
            .getBeaconStateSchema()
            .getFieldIndex(BeaconStateFields.VALIDATORS);
    return (entry, template, ssz) -> {
      if (entry.fieldIndex() != validatorsField
          || template.getSszLength() != ELEMENT_SIZE
          || ssz.isEmpty()
          || eligibleFrom(entry.hash(), ssz) > epoch) {
        return SupernodeFreezer.NONE.supernode(entry, template, ssz);
      }
      final int elementCount = ssz.size() / ELEMENT_SIZE;
      frozen.increment();
      frozenValidators.add(elementCount);
      final Bytes32 hash = entry.hash();
      return new FrozenSuperNode(
          entry.depth(), template, elementCount, hash, () -> loader.apply(hash));
    };
  }

  private long eligibleFrom(final Bytes32 hash, final Bytes ssz) {
    final Long memoized = eligibleFromEpoch.get(hash);
    if (memoized != null) {
      return memoized;
    }
    final long eligibleFrom = eligibleFromEpoch(ssz);
    eligibleFromEpoch.put(hash, eligibleFrom);
    return eligibleFrom;
  }

  /**
   * The first epoch at which every validator of the packed {@code ssz} is frozen-eligible, or
   * {@link #NEVER}.
   */
  public static long eligibleFromEpoch(final Bytes ssz) {
    if (ssz.size() % ELEMENT_SIZE != 0) {
      return NEVER;
    }
    final byte[] bytes = ssz.toArrayUnsafe();
    long eligibleFrom = 0;
    for (int offset = 0; offset < bytes.length; offset += ELEMENT_SIZE) {
      final long effectiveBalance = (long) LONG_LE.get(bytes, offset + EFFECTIVE_BALANCE_OFFSET);
      final long activationEpoch = (long) LONG_LE.get(bytes, offset + ACTIVATION_EPOCH_OFFSET);
      final long exitEpoch = (long) LONG_LE.get(bytes, offset + EXIT_EPOCH_OFFSET);
      final long withdrawableEpoch = (long) LONG_LE.get(bytes, offset + WITHDRAWABLE_EPOCH_OFFSET);
      // FAR_FUTURE_EPOCH is all ones, -1 as a signed long; real epochs are far below 2^63
      if (effectiveBalance != 0
          || activationEpoch < 0
          || exitEpoch < 0
          || withdrawableEpoch < 0
          || exitEpoch == Long.MAX_VALUE) {
        return NEVER;
      }
      eligibleFrom = Math.max(eligibleFrom, Math.max(withdrawableEpoch, exitEpoch + 1));
    }
    return eligibleFrom;
  }

  /** Supernodes frozen so far by every freezer of this instance. */
  public long frozenSupernodes() {
    return frozen.sum();
  }

  /** Validators in the supernodes frozen so far. */
  public long frozenValidators() {
    return frozenValidators.sum();
  }
}
