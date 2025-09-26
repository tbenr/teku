package tech.pegasys.teku.statetransition.datacolumns.retriever.recovering;

import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;
import tech.pegasys.teku.statetransition.datacolumns.db.DataColumnSidecarDbAccessor;
import tech.pegasys.teku.statetransition.datacolumns.retriever.DataColumnSidecarRetriever;

public class BlockSidecarsRecoveryTaskFactory {
  private final DataColumnSidecarRetriever delegate;
  private final KZG kzg;
  private final MiscHelpersFulu miscHelpers;
  private final DataColumnSidecarDbAccessor sidecarDB;
  private final AsyncRunner asyncRunner;
    private final int numberOfColumns;
  private final int numberOfColumnsRequired;

  public BlockSidecarsRecoveryTaskFactory(
          DataColumnSidecarRetriever delegate,
          KZG kzg,
          MiscHelpersFulu miscHelpers,
          DataColumnSidecarDbAccessor sidecarDB,
          AsyncRunner asyncRunner,
          int numberOfColumns) {
    this.delegate = delegate;
    this.kzg = kzg;
    this.miscHelpers = miscHelpers;
    this.sidecarDB = sidecarDB;
    this.asyncRunner = asyncRunner;
    this.numberOfColumns = numberOfColumns;
    this.numberOfColumnsRequired = Math.ceilDiv(numberOfColumns, 2);
  }

  public BlockSidecarsRecoveryTask create(final SlotAndBlockRoot slotAndBlockRoot) {
    return new BlockSidecarsRecoveryTask(
            slotAndBlockRoot, delegate, kzg, miscHelpers, sidecarDB, asyncRunner, numberOfColumns, numberOfColumnsRequired);
  }
}