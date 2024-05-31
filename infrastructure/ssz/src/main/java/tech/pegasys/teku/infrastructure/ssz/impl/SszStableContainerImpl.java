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

import tech.pegasys.teku.infrastructure.ssz.SszContainer;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszMutableContainer;
import tech.pegasys.teku.infrastructure.ssz.SszMutableStableContainer;
import tech.pegasys.teku.infrastructure.ssz.SszStableContainer;
import tech.pegasys.teku.infrastructure.ssz.cache.ArrayIntCache;
import tech.pegasys.teku.infrastructure.ssz.cache.IntCache;
import tech.pegasys.teku.infrastructure.ssz.schema.SszCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSparseCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SszStableContainerImpl extends AbstractSszSparseComposite<SszData> implements SszStableContainer {

  public SszStableContainerImpl(final SszStableContainerSchema<?> type) {
    this(type, type.getDefaultTree());
  }

  public SszStableContainerImpl(final SszStableContainerSchema<?> type, final TreeNode backingNode) {
    super(type, backingNode);
  }

  public SszStableContainerImpl(
          final SszSparseCompositeSchema<?> type, final TreeNode backingNode, final IntCache<Optional<SszData>> cache) {
    super(type, backingNode, cache);
  }

  @Override
  protected Optional<SszData> getImpl(final int index) {
    SszSparseCompositeSchema<?> type = this.getSchema();
    TreeNode node = getBackingNode().get(type.getChildGeneralizedIndex(index));
    return type.getChildSchema(index).createFromBackingNode(node);
  }

  @Override
  public AbstractSszStableContainerSchema<?> getSchema() {
    return (AbstractSszStableContainerSchema<?>) super.getSchema();
  }

  @Override
  public SszMutableStableContainer createWritableCopy() {
    return new SszMutableStableContainerImpl(this);
  }

  @Override
  protected int sizeImpl() {
    return (int) this.getSchema().getMaxLength();
  }

  @Override
  protected IntCache<Optional<SszData>> createCache() {
    return new ArrayIntCache<>(size());
  }

  @Override
  protected void checkIndex(final int index) {
    if (index >= size()) {
      throw new IndexOutOfBoundsException(
          "Invalid index " + index + " for container with size " + size());
    }
  }

  @Override
  public String toString() {
    return this.getSchema().getContainerName()
        + "{"
        + IntStream.range(0, this.getSchema().getFieldsCount())
            .mapToObj(idx -> this.getSchema().getFieldNames().get(idx) + "=" + get(idx))
            .collect(Collectors.joining(", "))
        + "}";
  }
}
