package dev.ssa.construction.spike.persistence;

public sealed interface OperationDelta permits InventoryDelta, WorldDelta {
    String evidenceKey();

    EvidenceSnapshot before();

    EvidenceSnapshot after();
}
