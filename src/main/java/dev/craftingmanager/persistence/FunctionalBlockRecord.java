package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;

public record FunctionalBlockRecord(BlockKey key, String definitionId) {
    public FunctionalBlockRecord {
        if (key == null || definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("functional block record requires key and definitionId");
        }
    }
}
