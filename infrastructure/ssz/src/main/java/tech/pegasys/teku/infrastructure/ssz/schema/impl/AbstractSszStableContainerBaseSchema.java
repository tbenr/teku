/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.infrastructure.ssz.schema.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Suppliers;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszStableContainerBase;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitvector;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszNone;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszStableContainerBaseSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszType;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszBitvectorSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema.NamedSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.json.SszStableContainerBaseTypeDefinition;
import tech.pegasys.teku.infrastructure.ssz.sos.SszDeserializeException;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;
import tech.pegasys.teku.infrastructure.ssz.sos.SszReader;
import tech.pegasys.teku.infrastructure.ssz.sos.SszWriter;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNodeSource;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNodeSource.CompressedBranchInfo;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNodeStore;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUtil;

// TODO memoizations for non-optional schema

/**
 * Implements the common container logic shared among Profile and StableContainer as per <a
 * href="https://eips.ethereum.org/EIPS/eip-7495">eip-7495</a> specifications.
 *
 * <p>With a combination of:
 *
 * <ol>
 *   <li>A NamedSchema list
 *   <li>A set of required field indices.
 *   <li>A set of optional field indices.
 *   <li>A theoretical future maximum field count.
 * </ol>
 *
 * this class can represent:
 *
 * <ol>
 *   <li>A StableContainer (empty required field indices, non-empty optional field indices)
 *   <li>A Profile (non-empty required field indices, optional field indices can be both empty and
 *       not-empty)
 * </ol>
 *
 * @param <C> the type of actual container class
 */
public abstract class AbstractSszStableContainerBaseSchema<C extends SszStableContainerBase>
    implements SszStableContainerBaseSchema<C> {
  public static final long CONTAINER_G_INDEX = GIndexUtil.LEFT_CHILD_G_INDEX;
  public static final long BITVECTOR_G_INDEX = GIndexUtil.RIGHT_CHILD_G_INDEX;

  private final Supplier<SszLengthBounds> sszLengthBounds =
      Suppliers.memoize(this::computeSszLengthBounds);
  private final String containerName;
  private final List<NamedSchema<?>> definedChildrenNamedSchemas;
  private final Object2IntMap<String> definedChildrenNamesToFieldIndex;
  private final List<String> definedChildrenNames;
  private final List<? extends SszSchema<?>> definedChildrenSchemas;
  private final int maxFieldCount;
  private final long treeWidth;
  private final SszBitvectorSchema<SszBitvector> activeFieldsSchema;
  private final SszBitvector requiredFields;
  private final SszBitvector optionalFields;
  private final boolean hasOptionalFields;
  // private final SszBitvector disallowedFields;
  private final TreeNode defaultTreeNode;

  private final DeserializableTypeDefinition<C> jsonTypeDefinition;

  private static long getContainerGIndex(final long rootGIndex) {
    return GIndexUtil.gIdxLeftGIndex(rootGIndex);
  }

  private static long getBitvectorGIndex(final long rootGIndex) {
    return GIndexUtil.gIdxRightGIndex(rootGIndex);
  }

  public AbstractSszStableContainerBaseSchema(
      final String name,
      final List<NamedSchema<?>> definedChildrenNamedSchemas,
      final Set<Integer> requiredFieldIndices,
      final Set<Integer> optionalFieldIndices,
      final int maxFieldCount) {
    checkArgument(
        optionalFieldIndices.stream().noneMatch(requiredFieldIndices::contains),
        "optional and active fields must not overlap");

    this.containerName = name;
    this.maxFieldCount = maxFieldCount;

    this.definedChildrenNamedSchemas = definedChildrenNamedSchemas;
    this.definedChildrenSchemas =
        definedChildrenNamedSchemas.stream().map(NamedSchema::getSchema).toList();
    this.activeFieldsSchema = SszBitvectorSchema.create(maxFieldCount);
    this.requiredFields = activeFieldsSchema.ofBits(requiredFieldIndices);
    this.optionalFields = activeFieldsSchema.ofBits(optionalFieldIndices);
    this.treeWidth = SszStableContainerBaseSchema.super.treeWidth();
    //    this.activeFieldIndicesCache =
    //            IntList.of(
    //                    requiredFieldIndices.stream()
    //                            .sorted(Comparator.naturalOrder())
    //                            .mapToInt(index -> getEffectiveChildIndex(index,
    // definedChildrenNamedSchemas))
    //                            .toArray());
    //
    //    this.optionalFieldIndicesCache = IntList.of(
    //            optionalFieldIndices.stream()
    //                    .sorted(Comparator.naturalOrder())
    //                    .mapToInt(index -> getEffectiveChildIndex(index,
    // definedChildrenNamedSchemas))
    //                    .toArray());

    this.definedChildrenNames =
        definedChildrenNamedSchemas.stream().map(NamedSchema::getName).toList();
    this.definedChildrenNamesToFieldIndex = new Object2IntOpenHashMap<>();
    for (int i = 0; i < definedChildrenNamedSchemas.size(); i++) {
      definedChildrenNamesToFieldIndex.put(definedChildrenNamedSchemas.get(i).getName(), i);
    }

    //    this.disallowedFields = activeFieldsSchema.ofBits(IntStream.range(0,
    // maxFieldCount).filter(i -> !(requiredFields.getBit(i) ||
    // optionalFields.getBit(i))).toArray());

    this.defaultTreeNode =
        BranchNode.create(
            createDefaultContainerTreeNode(requiredFieldIndices), requiredFields.getBackingNode());
    this.hasOptionalFields = optionalFields.getBitCount() > 0;
    this.jsonTypeDefinition = SszStableContainerBaseTypeDefinition.createFor(this);
  }

  protected TreeNode createDefaultContainerTreeNode(final Set<Integer> activeFieldIndices) {
    final List<TreeNode> defaultChildren = new ArrayList<>(getMaxFieldCount());
    for (int i = 0; i < getMaxFieldCount(); i++) {
      if (activeFieldIndices.contains(i)) {
        defaultChildren.add(getChildSchema(i).getDefaultTree());
      } else {
        defaultChildren.add(SszNone.INSTANCE.getBackingNode());
      }
    }
    return TreeUtil.createTree(defaultChildren);
  }

  @Override
  public DeserializableTypeDefinition<C> getJsonTypeDefinition() {
    return jsonTypeDefinition;
  }

  /** MaxLength exposes the effective potential numbers of fields */
  @Override
  public long getMaxLength() {
    // TODO memoize
    return requiredFields.getBitCount() + optionalFields.getBitCount();
  }

  /** The backing tree node is always filled up maxFieldCount, so maxChunks must reflect it */
  @Override
  public long maxChunks() {
    return (getMaxFieldCount() - 1) / getElementsPerChunk() + 1;
  }

  @Override
  public long treeWidth() {
    return treeWidth;
  }

  @Override
  public List<NamedSchema<?>> getChildrenNamedSchemas() {
    return definedChildrenNamedSchemas;
  }

  @Override
  public SszBitvector getActiveFieldsBitvectorFromBackingNode(final TreeNode node) {
    if (hasOptionalFields) {
      return activeFieldsSchema.createFromBackingNode(node.get(BITVECTOR_G_INDEX));
    }
    return requiredFields;
  }

  protected SszBitvector getRequiredFields() {
    return requiredFields;
  }

  @Override
  public int getMaxFieldCount() {
    return maxFieldCount;
  }

  @Override
  public TreeNode getDefaultTree() {
    return defaultTreeNode;
  }

  @Override
  public TreeNode createTreeFromFieldValues(final List<? extends SszData> fieldValues) {
    if (hasOptionalFields) {
      throw new UnsupportedOperationException(
          "createTreeFromFieldValues can be used only when the schema has no optional fields");
    }

    final int fieldsCount = getMaxFieldCount();
    checkArgument(fieldValues.size() <= fieldsCount, "Wrong number of filed values");

    final List<SszData> allFields = new ArrayList<>(fieldsCount);

    for (int index = 0, fieldIndex = 0; index < fieldsCount; index++) {
      if (fieldIndex >= fieldValues.size() || !requiredFields.getBit(index)) {
        allFields.add(SszNone.INSTANCE);
      } else {
        allFields.add(fieldValues.get(fieldIndex++));
      }
    }

    assert allFields.size() == fieldsCount;

    final TreeNode containerTree =
        TreeUtil.createTree(allFields.stream().map(SszData::getBackingNode).toList());

    return BranchNode.create(containerTree, requiredFields.getBackingNode());
  }

  public TreeNode createTreeFromOptionalFieldValues(
      final List<Optional<? extends SszData>> fieldValues) {
    final int fieldsCount = getMaxFieldCount();
    checkArgument(fieldValues.size() < fieldsCount, "Wrong number of filed values");

    final List<SszData> allFields = new ArrayList<>(fieldsCount);

    final IntList activeFieldIndices = new IntArrayList();

    for (int index = 0, fieldIndex = 0; index < fieldsCount; index++) {
      final Optional<? extends SszData> currentOptionalField;
      if (fieldIndex >= fieldValues.size()) {
        currentOptionalField = Optional.empty();
      } else {
        currentOptionalField = fieldValues.get(fieldIndex++);
      }

      if (currentOptionalField.isPresent()) {
        activeFieldIndices.add(index);
        allFields.add(currentOptionalField.get());
      } else {
        if (requiredFields.getBit(index)) {
          throw new IllegalArgumentException("supposed to be active");
        }
        allFields.add(SszNone.INSTANCE);
      }
    }

    assert allFields.size() == fieldsCount;

    final TreeNode activeFieldsTree =
        activeFieldsSchema.ofBits(activeFieldIndices).getBackingNode();
    final TreeNode containerTree =
        TreeUtil.createTree(allFields.stream().map(SszData::getBackingNode).toList());

    return BranchNode.create(containerTree, activeFieldsTree);
  }

  @Override
  public boolean isFixedSize() {
    if (hasOptionalFields) {
      // for containers with optional fields we behave as variable ssz
      return false;
    }

    return requiredFields
        .streamAllSetBits()
        .mapToObj(this::getChildSchema)
        .allMatch(SszType::isFixedSize);
  }

  @Override
  public int getSszFixedPartSize() {
    if (hasOptionalFields) {
      // for containers with optional fields we behave as variable ssz
      return 0;
    }

    return calcSszFixedPartSize(requiredFields);
  }

  @Override
  public int getSszVariablePartSize(final TreeNode node) {
    if (hasOptionalFields) {
      final SszBitvector activeFields = getActiveFieldsBitvectorFromBackingNode(node);

      final int containerSize =
          activeFields
              .streamAllSetBits()
              .map(
                  activeFieldIndex -> {
                    final SszSchema<?> schema = getChildSchema(activeFieldIndex);
                    final int childSize =
                        schema.getSszSize(node.get(getChildGeneralizedIndex(activeFieldIndex)));
                    return schema.isFixedSize() ? childSize : childSize + SSZ_LENGTH_SIZE;
                  })
              .sum();
      final int activeFieldsSize = activeFieldsSchema.getSszSize(activeFields.getBackingNode());
      return containerSize + activeFieldsSize;
    }

    if (isFixedSize()) {
      return 0;
    } else {

      return requiredFields
          .streamAllSetBits()
          .map(
              index -> {
                final SszSchema<?> childType = getChildSchema(index);
                return childType.getSszSize(node.get(getChildGeneralizedIndex(index)));
              })
          .sum();
    }
  }

  /**
   * Delegates serialization to deriving classes: Profile and StableContainer have different
   * serialization\deserialization rules
   */
  abstract int sszSerializeActiveFields(
      final SszBitvector activeFieldsBitvector, final SszWriter writer);

  @Override
  public int sszSerializeTree(final TreeNode node, final SszWriter writer) {
    final SszBitvector activeFieldsBitvector = getActiveFieldsBitvectorFromBackingNode(node);
    // we won't write active field when no optional fields are permitted
    final int activeFieldsWroteBytes = sszSerializeActiveFields(activeFieldsBitvector, writer);

    final TreeNode containerTree = node.get(CONTAINER_G_INDEX);

    int variableChildOffset = calcSszFixedPartSize(activeFieldsBitvector);

    final int[] variableSizes = new int[activeFieldsBitvector.size()];
    for (final OfInt activeIndicesIterator = activeFieldsBitvector.streamAllSetBits().iterator();
        activeIndicesIterator.hasNext(); ) {
      final int activeFieldIndex = activeIndicesIterator.next();

      final TreeNode childSubtree =
          containerTree.get(
              SszStableContainerBaseSchema.super.getChildGeneralizedIndex(activeFieldIndex));
      final SszSchema<?> childType = getChildSchema(activeFieldIndex);
      if (childType.isFixedSize()) {
        final int size = childType.sszSerializeTree(childSubtree, writer);
        assert size == childType.getSszFixedPartSize();
      } else {
        writer.write(SszType.sszLengthToBytes(variableChildOffset));
        final int childSize = childType.getSszSize(childSubtree);
        variableSizes[activeFieldIndex] = childSize;
        variableChildOffset += childSize;
      }
    }

    activeFieldsBitvector
        .streamAllSetBits()
        .forEach(
            activeFieldIndex -> {
              final SszSchema<?> childType = getChildSchema(activeFieldIndex);
              if (!childType.isFixedSize()) {
                final TreeNode childSubtree =
                    containerTree.get(
                        SszStableContainerBaseSchema.super.getChildGeneralizedIndex(
                            activeFieldIndex));
                final int size = childType.sszSerializeTree(childSubtree, writer);
                assert size == variableSizes[activeFieldIndex];
              }
            });

    return activeFieldsWroteBytes + variableChildOffset;
  }

  protected int calcSszFixedPartSize(final SszBitvector activeFieldBitvector) {
    return activeFieldBitvector
        .streamAllSetBits()
        .mapToObj(this::getChildSchema)
        .mapToInt(
            childType ->
                childType.isFixedSize() ? childType.getSszFixedPartSize() : SSZ_LENGTH_SIZE)
        .sum();
  }

  /**
   * Delegates deserialization to deriving classes: Profile and StableContainer have different
   * serialization\deserialization rules
   */
  abstract SszBitvector sszDeserializeActiveFieldsTree(final SszReader reader);

  @Override
  public TreeNode sszDeserializeTree(final SszReader reader) {
    final SszBitvector activeFields = sszDeserializeActiveFieldsTree(reader);
    return BranchNode.create(
        deserializeContainer(reader, activeFields), activeFields.getBackingNode());
  }

  private TreeNode deserializeContainer(final SszReader reader, final SszBitvector activeFields) {
    int endOffset = reader.getAvailableBytes();
    int childCount = getMaxFieldCount();
    final Queue<TreeNode> fixedChildrenSubtrees = new ArrayDeque<>(childCount);
    final IntList variableChildrenOffsets = new IntArrayList(childCount);
    activeFields
        .streamAllSetBits()
        .forEach(
            i -> {
              final SszSchema<?> childType = getChildSchema(i);
              if (childType.isFixedSize()) {
                try (SszReader sszReader = reader.slice(childType.getSszFixedPartSize())) {
                  TreeNode childNode = childType.sszDeserializeTree(sszReader);
                  fixedChildrenSubtrees.add(childNode);
                }
              } else {
                int childOffset = SszType.sszBytesToLength(reader.read(SSZ_LENGTH_SIZE));
                variableChildrenOffsets.add(childOffset);
              }
            });

    if (variableChildrenOffsets.isEmpty()) {
      if (reader.getAvailableBytes() > 0) {
        throw new SszDeserializeException("Invalid SSZ: unread bytes for fixed size container");
      }
    } else {
      if (variableChildrenOffsets.getInt(0) != endOffset - reader.getAvailableBytes()) {
        throw new SszDeserializeException(
            "First variable element offset doesn't match the end of fixed part");
      }
    }

    variableChildrenOffsets.add(endOffset);

    final ArrayDeque<Integer> variableChildrenSizes =
        new ArrayDeque<>(variableChildrenOffsets.size() - 1);
    for (int i = 0; i < variableChildrenOffsets.size() - 1; i++) {
      variableChildrenSizes.add(
          variableChildrenOffsets.getInt(i + 1) - variableChildrenOffsets.getInt(i));
    }

    if (variableChildrenSizes.stream().anyMatch(s -> s < 0)) {
      throw new SszDeserializeException("Invalid SSZ: wrong child offsets");
    }

    final List<TreeNode> childrenSubtrees = new ArrayList<>(childCount);
    for (int i = 0; i < childCount; i++) {
      if (!activeFields.getBit(i)) {
        childrenSubtrees.add(SszPrimitiveSchemas.NONE_SCHEMA.getDefaultTree());
        continue;
      }
      final SszSchema<?> childType = getChildSchema(i);
      if (childType.isFixedSize()) {
        childrenSubtrees.add(fixedChildrenSubtrees.remove());
        continue;
      }
      try (SszReader sszReader = reader.slice(variableChildrenSizes.remove())) {
        childrenSubtrees.add(childType.sszDeserializeTree(sszReader));
      }
    }

    return TreeUtil.createTree(childrenSubtrees);
  }

  public SszBitvectorSchema<SszBitvector> getActiveFieldsSchema() {
    return activeFieldsSchema;
  }

  @Override
  public long getChildGeneralizedIndex(final long elementIndex) {
    return GIndexUtil.gIdxCompose(
        CONTAINER_G_INDEX,
        SszStableContainerBaseSchema.super.getChildGeneralizedIndex(elementIndex));
  }

  @Override
  public SszLengthBounds getSszLengthBounds() {
    return sszLengthBounds.get();
  }

  /**
   * Bounds are calculate as follows:
   *
   * <ol>
   *   <li>Min bound is the min bound for the required only fields
   *   <li>Max bound is the max bound for the required fields and all optional fields
   * </ol>
   */
  private SszLengthBounds computeSszLengthBounds() {
    final SszLengthBounds requiredOnlyFieldsBounds =
        requiredFields
            .streamAllSetBits()
            .mapToObj(this::computeSingleSchemaLengthBounds)
            .reduce(SszLengthBounds.ZERO, SszLengthBounds::add);

    final SszLengthBounds includingOptionalFieldsBounds =
        optionalFields
            .streamAllSetBits()
            .mapToObj(this::computeSingleSchemaLengthBounds)
            .reduce(requiredOnlyFieldsBounds, SszLengthBounds::add);

    return requiredOnlyFieldsBounds.or(includingOptionalFieldsBounds);
  }

  private SszLengthBounds computeSingleSchemaLengthBounds(final int fieldIndex) {
    final SszSchema<?> schema = getChildSchema(fieldIndex);
    return schema
        .getSszLengthBounds()
        // dynamic sized children need 4-byte offset
        .addBytes((schema.isFixedSize() ? 0 : SSZ_LENGTH_SIZE))
        // elements are not packed in containers
        .ceilToBytes();
  }

  // TODO load and store

  @Override
  public void storeBackingNodes(
      final TreeNodeStore nodeStore,
      final int maxBranchLevelsSkipped,
      final long rootGIndex,
      final TreeNode node) {
    final TreeNode containerSubtree = node.get(CONTAINER_G_INDEX);
    final TreeNode activeFieldsBitvectorSubtree = node.get(BITVECTOR_G_INDEX);

    storeContainerBackingNodes(
        activeFieldsSchema.createFromBackingNode(activeFieldsBitvectorSubtree),
        nodeStore,
        maxBranchLevelsSkipped,
        getContainerGIndex(rootGIndex),
        containerSubtree);

    activeFieldsSchema.storeBackingNodes(
        nodeStore,
        maxBranchLevelsSkipped,
        getBitvectorGIndex(rootGIndex),
        activeFieldsBitvectorSubtree);

    nodeStore.storeBranchNode(
        node.hashTreeRoot(),
        rootGIndex,
        1,
        new Bytes32[] {
          containerSubtree.hashTreeRoot(), activeFieldsBitvectorSubtree.hashTreeRoot()
        });
  }

  private void storeContainerBackingNodes(
      final SszBitvector activeFields,
      final TreeNodeStore nodeStore,
      final int maxBranchLevelsSkipped,
      final long rootGIndex,
      final TreeNode node) {

    final int childDepth = treeDepth();
    if (childDepth == 0) {
      // Only one child so wrapper is omitted
      storeChildNode(nodeStore, maxBranchLevelsSkipped, rootGIndex, node);
      return;
    }
    final long lastUsefulGIndex =
        GIndexUtil.gIdxChildGIndex(rootGIndex, maxChunks() - 1, childDepth);
    StoringUtil.storeNodesToDepth(
        nodeStore,
        maxBranchLevelsSkipped,
        node,
        rootGIndex,
        childDepth,
        lastUsefulGIndex,
        (targetDepthNode, targetDepthGIndex) ->
            storeChildNode(
                nodeStore,
                maxBranchLevelsSkipped,
                targetDepthGIndex,
                targetDepthNode,
                activeFields));
  }

  public void storeChildNode(
      final TreeNodeStore nodeStore,
      final int maxBranchLevelsSkipped,
      final long gIndex,
      final TreeNode node,
      final SszBitvector activeFields) {
    final int childIndex = GIndexUtil.gIdxChildIndexFromGIndex(gIndex, treeDepth());
    if (activeFields.getBit(childIndex)) {
      final SszSchema<?> childSchema = getChildSchema(childIndex);
      childSchema.storeBackingNodes(nodeStore, maxBranchLevelsSkipped, gIndex, node);
    }
    SszPrimitiveSchemas.NONE_SCHEMA.storeBackingNodes(
        nodeStore, maxBranchLevelsSkipped, gIndex, node);
  }

  @Override
  public TreeNode loadBackingNodes(
      final TreeNodeSource nodeSource, final Bytes32 rootHash, final long rootGIndex) {
    if (TreeUtil.ZERO_TREES_BY_ROOT.containsKey(rootHash) || rootHash.equals(Bytes32.ZERO)) {
      return getDefaultTree();
    }
    final CompressedBranchInfo branchData = nodeSource.loadBranchNode(rootHash, rootGIndex);
    checkState(
        branchData.getChildren().length == 2,
        "Stable container root node must have exactly 2 children");
    checkState(branchData.getDepth() == 1, "Stable container root node must have depth of 1");
    final Bytes32 containerHash = branchData.getChildren()[0];
    final Bytes32 activeFieldsBitvectorHash = branchData.getChildren()[1];

    final TreeNode activeFieldsTreeNode =
        activeFieldsSchema.loadBackingNodes(
            nodeSource, activeFieldsBitvectorHash, getBitvectorGIndex(rootGIndex));

    final SszBitvector activeFields =
        activeFieldsSchema.createFromBackingNode(activeFieldsTreeNode);

    final long lastUsefulGIndex =
        GIndexUtil.gIdxChildGIndex(rootGIndex, maxChunks() - 1, treeDepth());
    final TreeNode containerTreeNode =
        LoadingUtil.loadNodesToDepth(
            nodeSource,
            containerHash,
            getContainerGIndex(rootGIndex),
            treeDepth(),
            defaultTreeNode.get(CONTAINER_G_INDEX),
            lastUsefulGIndex,
            (tns, childHash, childGIndex) ->
                loadChildNode(activeFields, tns, childHash, childGIndex));

    return BranchNode.create(containerTreeNode, activeFieldsTreeNode);
  }

  private TreeNode loadChildNode(
      final SszBitvector activeFields,
      final TreeNodeSource nodeSource,
      final Bytes32 childHash,
      final long childGIndex) {
    final int childIndex = GIndexUtil.gIdxChildIndexFromGIndex(childGIndex, treeDepth());
    if (activeFields.getBit(childIndex)) {
      return getChildSchema(childIndex).loadBackingNodes(nodeSource, childHash, childGIndex);
    }
    return SszPrimitiveSchemas.NONE_SCHEMA.loadBackingNodes(nodeSource, childHash, childGIndex);
  }

  @Override
  public SszSchema<?> getChildSchema(final int index) {
    return definedChildrenSchemas.get(index);
  }

  @Override
  public List<? extends SszSchema<?>> getFieldSchemas() {
    return definedChildrenSchemas;
  }

  /**
   * Get the index of a field by name
   *
   * @param fieldName the name of the field
   * @return The index if it exists, otherwise -1
   */
  @Override
  public int getFieldIndex(final String fieldName) {
    return definedChildrenNamesToFieldIndex.getOrDefault(fieldName, -1);
  }

  @Override
  public String getContainerName() {
    return !containerName.isEmpty() ? containerName : getClass().getName();
  }

  @Override
  public List<String> getFieldNames() {
    return definedChildrenNames;
  }

  @Override
  public final boolean hasOptionalFields() {
    return hasOptionalFields;
  }

  @Override
  public SszBitvector getDefaultActiveFields() {
    return requiredFields;
  }

  @Override
  public String toString() {
    return getContainerName();
  }
}
