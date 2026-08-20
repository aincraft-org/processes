package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.ItemSnapshot;

public record StationSlotRecord(BlockKey key, String slotId, ItemSnapshot item) {
    public StationSlotRecord {
        if (key == null || slotId == null || slotId.isBlank() || item == null) {
            throw new IllegalArgumentException("station slot identity and item are required");
        }
    }
}
