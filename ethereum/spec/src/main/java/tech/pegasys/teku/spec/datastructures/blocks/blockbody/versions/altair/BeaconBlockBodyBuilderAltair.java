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

package tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.altair;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.function.Function;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodyBuilder;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodySchema;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.phase0.BeaconBlockBodyBuilderPhase0;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;

public class BeaconBlockBodyBuilderAltair extends BeaconBlockBodyBuilderPhase0 {

  //  private final BeaconBlockBodySchemaAltairImpl schema;
  protected SyncAggregate syncAggregate;

  public BeaconBlockBodyBuilderAltair() {}

  @Override
  public Boolean supportsSyncAggregate() {
    return true;
  }

  @Override
  public BeaconBlockBodyBuilder syncAggregate(final SyncAggregate syncAggregate) {
    this.syncAggregate = syncAggregate;
    return this;
  }

  @Override
  protected void validate() {
    super.validate();
    checkNotNull(syncAggregate, "syncAggregate must be specified");
  }

  @Override
  public SafeFuture<BeaconBlockBody> build(
      final Function<Boolean, BeaconBlockBodySchema<?>> blindedToSchemaResolver) {
    validate();
    final BeaconBlockBodySchemaAltairImpl schema =
        getAndValidateSchema(blindedToSchemaResolver, BeaconBlockBodySchemaAltairImpl.class);

    return SafeFuture.completedFuture(
        new BeaconBlockBodyAltairImpl(
            schema,
            new SszSignature(randaoReveal),
            eth1Data,
            SszBytes32.of(graffiti),
            proposerSlashings,
            attesterSlashings,
            attestations,
            deposits,
            voluntaryExits,
            syncAggregate));
  }
}
