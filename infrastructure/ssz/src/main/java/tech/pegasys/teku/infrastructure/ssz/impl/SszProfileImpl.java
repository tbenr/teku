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

package tech.pegasys.teku.infrastructure.ssz.impl;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszProfile;
import tech.pegasys.teku.infrastructure.ssz.cache.ArrayIntCache;
import tech.pegasys.teku.infrastructure.ssz.cache.IntCache;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitvector;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProfileSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

public class SszProfileImpl extends SszContainerImpl implements SszProfile {

  public SszProfileImpl(final SszProfileSchema<?> type) {
    super(type);
  }

  public SszProfileImpl(final SszProfileSchema<?> type, final TreeNode backingNode) {
    super(type, backingNode);
  }

  public SszProfileImpl(
      final SszProfileSchema<?> type, final TreeNode backingNode, final IntCache<SszData> cache) {
    super(type, backingNode, cache);
  }

  public SszProfileImpl(final SszProfileSchema<?> type, final SszData... memberValues) {
    super(
        type,
        type.createTreeFromFieldValues(Arrays.asList(memberValues)),
        createCache(memberValues));

    for (int i = 0; i < memberValues.length; i++) {
      Preconditions.checkArgument(
          memberValues[i].getSchema().equals(type.getChildSchema(i)),
          "Wrong child schema at index %s. Expected: %s, was %s",
          i,
          type.getChildSchema(i),
          memberValues[i].getSchema());
    }
  }

  @Override
  protected void checkIndex(final int index) {
    super.checkIndex(index);
    if (!isFieldActive(index)) {
      throw new NoSuchElementException("Index " + index + " is not active in this profile");
    }
  }

  @Override
  public boolean isFieldActive(final int index) {
    return getActiveFields().getBit(index);
  }

  @Override
  public SszBitvector getActiveFields() {
    return getSchema().toProfileSchema().orElseThrow().getActiveFields();
  }

  private static IntCache<SszData> createCache(final SszData... memberValues) {
    ArrayIntCache<SszData> cache = new ArrayIntCache<>(memberValues.length);
    for (int i = 0; i < memberValues.length; i++) {
      cache.invalidateWithNewValue(i, memberValues[i]);
    }
    return cache;
  }

  @Override
  public String toString() {
    return getSchema().getContainerName()
        + "{activeFields="
        + getActiveFields()
        + ", "
        + getActiveFields()
            .streamAllSetBits()
            .mapToObj(idx -> getSchema().getFieldNames().get(idx) + "=" + get(idx))
            .collect(Collectors.joining(", "))
        + "}";
  }
}