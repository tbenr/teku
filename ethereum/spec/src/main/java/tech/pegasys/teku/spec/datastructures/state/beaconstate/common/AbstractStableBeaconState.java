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

package tech.pegasys.teku.spec.datastructures.state.beaconstate.common;

import com.google.common.base.MoreObjects.ToStringHelper;
import tech.pegasys.teku.infrastructure.ssz.SszContainer;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.cache.IntCache;
import tech.pegasys.teku.infrastructure.ssz.cache.SoftRefIntCache;
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszImmutableStableContainer;
import tech.pegasys.teku.infrastructure.ssz.schema.SszCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateCache;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateStableSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.MutableBeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.StableBeaconState;

public abstract class AbstractStableBeaconState<TMutable extends MutableBeaconState>
    extends AbstractSszImmutableStableContainer implements StableBeaconState, BeaconStateCache {

  private final TransitionCaches transitionCaches;
  private final SlotCaches slotCaches;

  @SuppressWarnings("unchecked")
  protected AbstractStableBeaconState(final BeaconStateStableSchema<?, ?> schema) {
    super((SszStableContainerSchema<? extends AbstractSszImmutableStableContainer>) schema);
    this.transitionCaches = TransitionCaches.createNewEmpty();
    this.slotCaches = SlotCaches.createNewEmpty();
  }

  protected AbstractStableBeaconState(
      final SszCompositeSchema<?> type,
      final TreeNode backingNode,
      final IntCache<SszData> cache,
      final TransitionCaches transitionCaches,
      final SlotCaches slotCaches) {
    super(
            type,
        backingNode,
        cache);
    this.transitionCaches = transitionCaches;
    this.slotCaches = slotCaches;
  }

  @SuppressWarnings("unchecked")
  protected AbstractStableBeaconState(
      final AbstractSszContainerSchema<? extends SszContainer> type, final TreeNode backingNode) {
    super(
        (SszStableContainerSchema<? extends AbstractSszImmutableStableContainer>) type,
        backingNode);
    this.transitionCaches = TransitionCaches.createNewEmpty();
    this.slotCaches = SlotCaches.createNewEmpty();
  }

  @Override
  public BeaconStateStableSchema<?, ?> getBeaconStateSchema() {
    return (BeaconStateStableSchema<?, ?>) getSchema();
  }

  @Override
  public <E1 extends Exception, E2 extends Exception, E3 extends Exception> BeaconState updated(
      final Mutator<MutableBeaconState, E1, E2, E3> mutator) throws E1, E2, E3 {
    MutableBeaconState writableCopy = createWritableCopy();
    mutator.mutate(writableCopy);
    return writableCopy.commitChanges();
  }

  @Override
  public int hashCode() {
    return BeaconStateInvariants.hashCode(this);
  }

  @Override
  public boolean equals(final Object obj) {
    return BeaconStateInvariants.equals(this, obj);
  }

  @Override
  public TransitionCaches getTransitionCaches() {
    return transitionCaches;
  }

  @Override
  public SlotCaches getSlotCaches() {
    return slotCaches;
  }

  @Override
  protected IntCache<SszData> createCache() {
    return new SoftRefIntCache<>(super::createCache);
  }

  @Override
  public String toString() {
    return BeaconStateInvariants.toString(this, this::describeCustomFields);
  }

  protected abstract void describeCustomFields(ToStringHelper stringBuilder);

  @Override
  public abstract TMutable createWritableCopy();
}
