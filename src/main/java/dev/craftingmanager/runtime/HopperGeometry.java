package dev.craftingmanager.runtime;

import dev.craftingmanager.api.Domain.ProcessFace;

public final class HopperGeometry {
    private HopperGeometry() {}

    public static ProcessFace insertionFace(ProcessFace hopperFacing) {
        return hopperFacing.opposite();
    }

    public static ProcessFace extractionFace() {
        return ProcessFace.DOWN;
    }
}
