/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.infrastructure.ssz.impl;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import tech.pegasys.teku.infrastructure.ssz.InvalidValueSchemaException;
import tech.pegasys.teku.infrastructure.ssz.SszComposite;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszMutableComposite;
import tech.pegasys.teku.infrastructure.ssz.SszMutableData;
import tech.pegasys.teku.infrastructure.ssz.SszMutableRefComposite;
import tech.pegasys.teku.infrastructure.ssz.SszMutableRefSparseComposite;
import tech.pegasys.teku.infrastructure.ssz.SszMutableSparseComposite;
import tech.pegasys.teku.infrastructure.ssz.SszSparseComposite;
import tech.pegasys.teku.infrastructure.ssz.cache.IntCache;
import tech.pegasys.teku.infrastructure.ssz.schema.SszCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSparseCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeUpdates;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base backing {@link SszMutableData} class for mutable composite ssz structures (lists, vectors,
 * containers)
 *
 * <p>It has corresponding backing immutable {@link SszData} and the set of changed children. When
 * the {@link #commitChanges()} is called a new immutable {@link SszData} instance is created where
 * changes accumulated in this instance are merged with cached backing {@link SszData} instance
 * which weren't changed.
 *
 * <p>If this ssz data is get by reference from its parent composite view ({@link
 * SszMutableRefComposite#getByRef(int)} then all the changes are notified to the parent view (see
 * {@link SszMutableComposite#setInvalidator(Consumer)}
 *
 * <p>The mutable structures based on this class are inherently NOT thread safe
 */
public abstract class AbstractSszMutableSparseComposite<
        SszChildT extends SszData, SszMutableChildT extends SszChildT>
    implements SszMutableRefSparseComposite<SszChildT, SszMutableChildT> {

  protected AbstractSszSparseComposite<SszChildT> backingImmutableData;
  private Consumer<SszMutableData> invalidator;
  private final Int2ObjectSortedMap<ChildChangeRecord<SszChildT, SszMutableChildT>>
      childrenChanges = new Int2ObjectRBTreeMap<>();
  private Integer sizeCache;
  private final SszSparseCompositeSchema<?> cachedSchema;

  private ChildChangeRecord<SszChildT, SszMutableChildT> createChangeRecordByValue(
      final Optional<SszChildT> newValue) {
    return new ChildChangeRecord<>(newValue, null);
  }

  private ChildChangeRecord<SszChildT, SszMutableChildT> createChangeRecordByRef(
      final Optional<SszMutableChildT> childRef) {
    return new ChildChangeRecord<>(null, childRef);
  }

  /** Creates a new mutable instance with backing immutable data */
  protected AbstractSszMutableSparseComposite(
      final AbstractSszSparseComposite<SszChildT> backingImmutableData) {
    this.backingImmutableData = backingImmutableData;
    sizeCache = backingImmutableData.size();
    cachedSchema = backingImmutableData.getSchema();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void set(final int index, final Optional<SszChildT> value) {
    checkIndex(index, true);
    checkNotNull(value);
    validateChildSchema(index, value);

    final Optional<SszChildT> immutableValue;
    if (value.isPresent() && value.get() instanceof SszMutableData) {
      immutableValue = Optional.of((SszChildT) ((SszMutableData) value.get()).commitChanges());
    } else {
      immutableValue = value;
    }

    childrenChanges.put(index, createChangeRecordByValue(immutableValue));

    sizeCache = index >= sizeCache ? index + 1 : sizeCache;
    invalidate();
  }

  protected void validateChildSchema(final int index, final Optional<SszChildT> value) {
    if(value.isEmpty()) {
      return;
    }
    if (!value.get().getSchema().equals(getSchema().getChildSchema(index))) {
      throw new InvalidValueSchemaException(
          "Expected child to have schema "
              + getSchema().getChildSchema(index)
              + ", but value has schema "
              + value.get().getSchema());
    }
  }

  @Override
  public Optional<SszChildT> get(final int index) {
    checkIndex(index, false);
    ChildChangeRecord<SszChildT, SszMutableChildT> changeRecord = childrenChanges.get(index);
    if (changeRecord == null) {
      return backingImmutableData.get(index);
    } else if (changeRecord.isByValue()) {
      return changeRecord.getNewValue();
    } else {
      return (Optional<SszChildT>) changeRecord.getRefValue();
    }
  }

  @Override
  public Optional<SszMutableChildT> getByRef(final int index) {
    ChildChangeRecord<SszChildT, SszMutableChildT> changeRecord = childrenChanges.get(index);
    if (changeRecord != null && changeRecord.isByRef()) {
      return changeRecord.getRefValue();
    } else {
      Optional<SszChildT> readView = get(index);
      if(readView.isEmpty()) {
        return Optional.empty();
      }
      @SuppressWarnings("unchecked")
      SszMutableChildT w = (SszMutableChildT) readView.get().createWritableCopy();
      ChildChangeRecord<SszChildT, SszMutableChildT> newChangeRecord = createChangeRecordByRef(w);
      childrenChanges.put(index, newChangeRecord);
      if (w instanceof SszMutableComposite) {
        ((SszMutableComposite<?>) w).setInvalidator(viewWrite -> invalidate());
      }
      return newChangeRecord.getRefValue();
    }
  }

  @Override
  public SszSparseCompositeSchema<?> getSchema() {
    return cachedSchema;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void clear() {
    backingImmutableData = (AbstractSszSparseComposite<SszChildT>) getSchema().getDefault();
    childrenChanges.clear();
    sizeCache = backingImmutableData.size();
    invalidate();
  }

  @Override
  public int size() {
    return sizeCache;
  }

  @Override
  @SuppressWarnings("unchecked")
  public SszSparseComposite<SszChildT> commitChanges() {
    if (childrenChanges.isEmpty()) {
      return backingImmutableData;
    } else {
      IntCache<Optional<SszChildT>> cache = backingImmutableData.transferCache();
      Stream<Map.Entry<Integer, SszChildT>> changesList =
          childrenChanges.int2ObjectEntrySet().stream()
              .map(
                  entry -> {
                    ChildChangeRecord<SszChildT, SszMutableChildT> changeRecord = entry.getValue();
                    Integer childIndex = entry.getIntKey();
                    final SszChildT newValue;
                    if (changeRecord.isByValue()) {
                      newValue = changeRecord.getNewValue();
                    } else {
                      newValue =
                          (SszChildT) ((SszMutableData) changeRecord.getRefValue()).commitChanges();
                    }
                    return Map.entry(childIndex, newValue);
                  })
              // pre-fill the read cache with changed values
              .peek(e -> cache.invalidateWithNewValue(e.getKey(), e.getValue()));
      TreeNode originalBackingTree = backingImmutableData.getBackingNode();
      TreeUpdates changes = changesToNewNodes(changesList, originalBackingTree);
      TreeNode newBackingTree = originalBackingTree.updated(changes);
      TreeNode finalBackingTree = doFinalTreeUpdates(newBackingTree);
      return createImmutableSszComposite(finalBackingTree, cache);
    }
  }

  protected TreeNode doFinalTreeUpdates(final TreeNode updatedTree) {
    return updatedTree;
  }

  /** Converts a set of changed view with their indices to the {@link TreeUpdates} instance */
  protected TreeUpdates changesToNewNodes(
      final Stream<Map.Entry<Integer, SszChildT>> newChildValues, final TreeNode original) {
    SszCompositeSchema<?> type = getSchema();
    if (type.getElementsPerChunk() > 1) {
      throw new IllegalStateException(
          "Packed primitive types are not supported by this implementation");
    }
    return newChildValues
        .map(
            e ->
                new TreeUpdates.Update(
                    type.getChildGeneralizedIndex(e.getKey()), e.getValue().getBackingNode()))
        .collect(TreeUpdates.collector());
  }

  /**
   * Should be implemented by subclasses to create respectful immutable view with backing tree and
   * views cache
   */
  protected abstract AbstractSszSparseComposite<SszChildT> createImmutableSszComposite(
      TreeNode backingNode, IntCache<Optional<SszChildT>> viewCache);

  @Override
  public void setInvalidator(final Consumer<SszMutableData> listener) {
    invalidator = listener;
  }

  protected void invalidate() {
    if (invalidator != null) {
      invalidator.accept(this);
    }
  }

  /** Creating nested mutable copies is not supported yet */
  @Override
  public SszMutableSparseComposite<SszChildT> createWritableCopy() {
    throw new UnsupportedOperationException(
        "createWritableCopy() is now implemented for immutable SszData only");
  }

  /**
   * Checks the child index for get or set
   *
   * @throws IndexOutOfBoundsException is index is not valid
   */
  protected abstract void checkIndex(int index, boolean set);

  private static final class ChildChangeRecord<
      SszChildT extends SszData, SszMutableChildT extends SszChildT> {

    private final Optional<SszChildT> newValue;
    private final Optional<SszMutableChildT> refValue;

    private ChildChangeRecord(final Optional<SszChildT> newValue, final Optional<SszMutableChildT> refValue) {
      this.newValue = newValue;
      this.refValue = refValue;
    }

    public boolean isByRef() {
      return refValue != null;
    }

    public boolean isByValue() {
      return newValue != null;
    }

    public Optional<SszChildT> getNewValue() {
      return newValue;
    }

    public Optional<SszMutableChildT> getRefValue() {
      return refValue;
    }
  }
}
