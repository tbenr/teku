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

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffer;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;

/**
 * Binds the generic tree diff engine to beacon states: picks the state schema in effect at a slot
 * and the fields stored as uint64 element deltas ({@code balances} and, from Altair, {@code
 * inactivity_scores}).
 *
 * <p>Callers diff {@code state.getBackingNode()} trees and rebuild states with {@code
 * schema.createFromBackingNode(tree)}. A diff is only meaningful between trees of the same schema,
 * so a base from another milestone (see {@link #sameMilestone}) needs a snapshot instead.
 *
 * <p>Differs and appliers are memoized per milestone. An applier takes the supernode source of each
 * application as an argument, so one serves every reader.
 */
public class BeaconStateDiffSupport {

  private static final SszFieldName[] DELTA_FIELDS = {
    BeaconStateFields.BALANCES, BeaconStateFields.INACTIVITY_SCORES
  };

  private final Spec spec;
  private final Map<SpecMilestone, TreeDiffer> differs = new ConcurrentHashMap<>();
  private final Map<SpecMilestone, TreeDiffApplier> appliers = new ConcurrentHashMap<>();

  public BeaconStateDiffSupport(final Spec spec) {
    this.spec = spec;
  }

  public TreeDiffer differFor(final UInt64 slot) {
    return differs.computeIfAbsent(
        milestoneAt(slot),
        milestone -> {
          final BeaconStateSchema<?, ?> schema = schemaFor(slot);
          return new TreeDiffer(schema, deltaFieldIndices(schema));
        });
  }

  public TreeDiffApplier applierFor(final UInt64 slot) {
    return appliers.computeIfAbsent(
        milestoneAt(slot), milestone -> new TreeDiffApplier(schemaFor(slot)));
  }

  public BeaconStateSchema<?, ?> schemaFor(final UInt64 slot) {
    return spec.atSlot(slot).getSchemaDefinitions().getBeaconStateSchema();
  }

  public IntSet deltaFieldIndices(final BeaconStateSchema<?, ?> schema) {
    final IntSet indices = new IntOpenHashSet();
    for (final SszFieldName field : DELTA_FIELDS) {
      final int index = schema.getFieldIndex(field);
      if (index >= 0) {
        indices.add(index);
      }
    }
    return IntSets.unmodifiable(indices);
  }

  public boolean sameMilestone(final UInt64 slotA, final UInt64 slotB) {
    return milestoneAt(slotA) == milestoneAt(slotB);
  }

  private SpecMilestone milestoneAt(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone();
  }
}
