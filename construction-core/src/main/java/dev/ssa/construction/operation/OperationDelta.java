package dev.ssa.construction.operation;

public sealed interface OperationDelta permits InventoryDelta, WorldDelta {
    String evidenceKey();

    EvidenceSnapshot before();

    EvidenceSnapshot after();
}
