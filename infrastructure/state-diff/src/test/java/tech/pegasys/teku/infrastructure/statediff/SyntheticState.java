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

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.RandomSszDataGenerator;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.SszMutableList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszByteList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszMutablePrimitiveList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszMutableUInt64List;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.impl.SszContainerImpl;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchemaHints;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszByteListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszByteVectorSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * A small beacon-state-like container used to exercise the tree differ: a fixed uint64 and bytes32,
 * a supernode-packed list of 121-byte validator-like containers, a uint64 list stored as deltas and
 * two byte lists that rotate like participation flags.
 */
final class SyntheticState {

  static final int A = 0;
  static final int B = 1;
  static final int VALIDATORS = 2;
  static final int BALANCES = 3;
  static final int FLAGS = 4;
  static final int PREV = 5;

  static final int VALIDATOR_EFFECTIVE_BALANCE = 2;
  static final int SUPERNODE_DEPTH = 8;
  static final long MAX_LIST_LENGTH = 1L << 20;

  static final SszContainerSchema<SszContainerImpl> VALIDATOR_SCHEMA =
      SszContainerSchema.create(
          List.of(
              SszByteVectorSchema.create(48),
              SszPrimitiveSchemas.BYTES32_SCHEMA,
              SszPrimitiveSchemas.UINT64_SCHEMA,
              SszPrimitiveSchemas.BIT_SCHEMA,
              SszPrimitiveSchemas.UINT64_SCHEMA,
              SszPrimitiveSchemas.UINT64_SCHEMA,
              SszPrimitiveSchemas.UINT64_SCHEMA,
              SszPrimitiveSchemas.UINT64_SCHEMA),
          SszContainerImpl::new);

  static final SszListSchema<SszContainerImpl, ?> VALIDATORS_SCHEMA =
      SszListSchema.create(
          VALIDATOR_SCHEMA, MAX_LIST_LENGTH, SszSchemaHints.sszSuperNode(SUPERNODE_DEPTH));
  static final SszUInt64ListSchema<SszUInt64List> BALANCES_SCHEMA =
      SszUInt64ListSchema.create(MAX_LIST_LENGTH);
  static final SszByteListSchema<SszByteList> FLAGS_SCHEMA =
      SszByteListSchema.create(MAX_LIST_LENGTH);
  static final SszByteListSchema<SszByteList> PREV_SCHEMA =
      SszByteListSchema.create(MAX_LIST_LENGTH);

  static final SszContainerSchema<SszContainerImpl> SCHEMA =
      SszContainerSchema.create(
          List.of(
              SszPrimitiveSchemas.UINT64_SCHEMA,
              SszPrimitiveSchemas.BYTES32_SCHEMA,
              VALIDATORS_SCHEMA,
              BALANCES_SCHEMA,
              FLAGS_SCHEMA,
              PREV_SCHEMA),
          SszContainerImpl::new);

  static final IntSet DELTA_FIELDS = IntSet.of(BALANCES);

  private SyntheticState() {}

  static long fieldGIndex(final int fieldIndex) {
    return SCHEMA.getChildGeneralizedIndex(fieldIndex);
  }

  /**
   * Builds a populated state and round trips it through SSZ so the validators list is backed by
   * {@link SszSuperNode}s, as states loaded from disk are.
   */
  static TreeNode create(final Random random, final int validatorCount) {
    TreeNode tree = SCHEMA.getDefaultTree();
    tree = setA(tree, random.nextLong());
    tree = setB(tree, Bytes32.random(random));
    tree = appendValidators(tree, random, validatorCount);
    tree = appendBalances(tree, random, validatorCount);
    tree =
        withField(tree, FLAGS, appendFlags(FLAGS_SCHEMA.getDefaultTree(), random, validatorCount));
    tree = withField(tree, PREV, appendFlags(PREV_SCHEMA.getDefaultTree(), random, validatorCount));
    return deserialize(serialize(tree));
  }

  static Bytes serialize(final TreeNode tree) {
    return SCHEMA.createFromBackingNode(tree).sszSerialize();
  }

  static TreeNode deserialize(final Bytes ssz) {
    return SCHEMA.sszDeserialize(ssz).getBackingNode();
  }

  static TreeNode withField(final TreeNode tree, final int fieldIndex, final TreeNode fieldNode) {
    return tree.updated(fieldGIndex(fieldIndex), fieldNode);
  }

  static TreeNode setA(final TreeNode tree, final long value) {
    return withField(tree, A, SszUInt64.of(UInt64.fromLongBits(value)).getBackingNode());
  }

  static TreeNode setB(final TreeNode tree, final Bytes32 value) {
    return withField(tree, B, SszBytes32.of(value).getBackingNode());
  }

  static SszList<SszContainerImpl> validators(final TreeNode tree) {
    return VALIDATORS_SCHEMA.createFromBackingNode(tree.get(fieldGIndex(VALIDATORS)));
  }

  static TreeNode setValidatorField(
      final TreeNode tree, final int index, final int fieldIndex, final SszData value) {
    final SszList<SszContainerImpl> validators = validators(tree);
    final TreeNode element =
        validators
            .get(index)
            .getBackingNode()
            .updated(VALIDATOR_SCHEMA.getChildGeneralizedIndex(fieldIndex), value.getBackingNode());
    final SszMutableList<SszContainerImpl> mutable = validators.createWritableCopy();
    mutable.set(index, VALIDATOR_SCHEMA.createFromBackingNode(element));
    return withField(tree, VALIDATORS, mutable.commitChanges().getBackingNode());
  }

  static TreeNode setRandomValidatorField(
      final TreeNode tree, final Random random, final int index, final int fieldIndex) {
    final SszData value =
        new RandomSszDataGenerator(random, 0)
            .randomData(VALIDATOR_SCHEMA.getChildSchema(fieldIndex));
    return setValidatorField(tree, index, fieldIndex, value);
  }

  static TreeNode appendValidators(final TreeNode tree, final Random random, final int count) {
    final SszMutableList<SszContainerImpl> mutable = validators(tree).createWritableCopy();
    final RandomSszDataGenerator generator = new RandomSszDataGenerator(random, 0);
    for (int i = 0; i < count; i++) {
      mutable.append(generator.randomData(VALIDATOR_SCHEMA));
    }
    return withField(tree, VALIDATORS, mutable.commitChanges().getBackingNode());
  }

  static SszUInt64List balances(final TreeNode tree) {
    return BALANCES_SCHEMA.createFromBackingNode(tree.get(fieldGIndex(BALANCES)));
  }

  static TreeNode setBalance(final TreeNode tree, final int index, final long value) {
    final SszMutableUInt64List mutable = balances(tree).createWritableCopy();
    mutable.setElement(index, UInt64.fromLongBits(value));
    return withField(tree, BALANCES, mutable.commitChanges().getBackingNode());
  }

  static TreeNode appendBalances(final TreeNode tree, final Random random, final int count) {
    final SszMutableUInt64List mutable = balances(tree).createWritableCopy();
    for (int i = 0; i < count; i++) {
      mutable.appendElement(UInt64.fromLongBits(random.nextLong()));
    }
    return withField(tree, BALANCES, mutable.commitChanges().getBackingNode());
  }

  static SszByteList flags(final TreeNode tree) {
    return FLAGS_SCHEMA.createFromBackingNode(tree.get(fieldGIndex(FLAGS)));
  }

  static TreeNode setFlag(final TreeNode tree, final int index, final byte value) {
    final SszMutablePrimitiveList<Byte, SszByte> mutable = flags(tree).createWritableCopy();
    mutable.setElement(index, value);
    return withField(tree, FLAGS, mutable.commitChanges().getBackingNode());
  }

  static TreeNode appendFlags(final TreeNode listNode, final Random random, final int count) {
    final SszMutablePrimitiveList<Byte, SszByte> mutable =
        FLAGS_SCHEMA.createFromBackingNode(listNode).createWritableCopy();
    for (int i = 0; i < count; i++) {
      mutable.appendElement((byte) random.nextInt(8));
    }
    return mutable.commitChanges().getBackingNode();
  }

  /** Appends a zero flag, as processing a deposit does; the flags data root may not change. */
  static TreeNode appendZeroFlag(final TreeNode tree) {
    final SszMutablePrimitiveList<Byte, SszByte> mutable = flags(tree).createWritableCopy();
    mutable.appendElement((byte) 0);
    return withField(tree, FLAGS, mutable.commitChanges().getBackingNode());
  }

  /** {@code prev <- flags}, as the participation flag rotation does. */
  static TreeNode rotateFlags(final TreeNode tree) {
    return withField(tree, PREV, tree.get(fieldGIndex(FLAGS)));
  }

  static TreeNode clearFlags(final TreeNode tree) {
    return withField(tree, FLAGS, FLAGS_SCHEMA.getDefaultTree());
  }

  /** Resets the flags to {@code count} zero bytes the way the epoch processor does. */
  static TreeNode zeroFlags(final TreeNode tree, final int count) {
    final SszMutablePrimitiveList<Byte, SszByte> mutable =
        FLAGS_SCHEMA.getDefault().createWritableCopy();
    mutable.setAll(SszByte.ZERO, count);
    return withField(tree, FLAGS, mutable.commitChanges().getBackingNode());
  }

  static boolean containsSupernode(final TreeNode node) {
    if (node instanceof SszSuperNode superNode) {
      return !superNode.getData().isEmpty();
    }
    if (node instanceof BranchNode branch
        && !TreeUtil.ZERO_TREES_BY_ROOT.containsKey(node.hashTreeRoot())) {
      return containsSupernode(branch.left()) || containsSupernode(branch.right());
    }
    return false;
  }
}
