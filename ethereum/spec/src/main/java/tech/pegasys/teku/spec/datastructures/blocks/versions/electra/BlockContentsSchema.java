/*
 * Copyright Consensys Software Inc., 2023
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

package tech.pegasys.teku.spec.datastructures.blocks.versions.electra;

import java.util.List;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema4;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.kzg.KZGProof;
import tech.pegasys.teku.spec.config.SpecConfigDeneb;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.Blob;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSchema;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockSchema;
import tech.pegasys.teku.spec.datastructures.blocks.BlockContainerSchema;
import tech.pegasys.teku.spec.datastructures.execution.versions.electra.InclusionList;
import tech.pegasys.teku.spec.datastructures.execution.versions.electra.InclusionListSchema;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProof;
import tech.pegasys.teku.spec.datastructures.type.SszKZGProofSchema;

public class BlockContentsSchema
    extends ContainerSchema4<
        BlockContents, BeaconBlock, SszList<SszKZGProof>, SszList<Blob>, InclusionList>
    implements BlockContainerSchema<BlockContents> {

  static final SszFieldName FIELD_KZG_PROOFS = () -> "kzg_proofs";
  static final SszFieldName FIELD_BLOBS = () -> "blobs";

  BlockContentsSchema(
      final String containerName,
      final SpecConfigDeneb specConfig,
      final BeaconBlockSchema beaconBlockSchema,
      final BlobSchema blobSchema,
      final InclusionListSchema inclusionListSchema) {
    super(
        containerName,
        namedSchema("block", beaconBlockSchema),
        namedSchema(
            FIELD_KZG_PROOFS,
            SszListSchema.create(SszKZGProofSchema.INSTANCE, specConfig.getMaxBlobsPerBlock())),
        namedSchema(
            FIELD_BLOBS, SszListSchema.create(blobSchema, specConfig.getMaxBlobsPerBlock())),
        namedSchema("inclusion_list", inclusionListSchema));
  }

  public static BlockContentsSchema create(
      final SpecConfigDeneb specConfig,
      final BeaconBlockSchema beaconBlockSchema,
      final BlobSchema blobSchema,
      final InclusionListSchema inclusionListSchema,
      final String containerName) {
    return new BlockContentsSchema(
        containerName, specConfig, beaconBlockSchema, blobSchema, inclusionListSchema);
  }

  public BlockContents create(
      final BeaconBlock beaconBlock,
      final List<KZGProof> kzgProofs,
      final List<Blob> blobs,
      final InclusionList inclusionList) {
    return new BlockContents(this, beaconBlock, kzgProofs, blobs, inclusionList);
  }

  @Override
  public BlockContents createFromBackingNode(final TreeNode node) {
    return new BlockContents(this, node);
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<SszKZGProof, ?> getKzgProofsSchema() {
    return (SszListSchema<SszKZGProof, ?>) getChildSchema(getFieldIndex(FIELD_KZG_PROOFS));
  }

  @SuppressWarnings("unchecked")
  public SszListSchema<Blob, ?> getBlobsSchema() {
    return (SszListSchema<Blob, ?>) getChildSchema(getFieldIndex(FIELD_BLOBS));
  }

  @Override
  public Optional<BlockContentsSchema> toVersionElectra() {
    return Optional.of(this);
  }
}
