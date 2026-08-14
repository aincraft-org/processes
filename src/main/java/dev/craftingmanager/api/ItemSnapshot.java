package dev.craftingmanager.api;

import java.util.Map;

public record ItemSnapshot(String material, int amount, Map<String, String> metadata) {
    public ItemSnapshot {
        if (material == null || material.isBlank()) throw new IllegalArgumentException("material is required");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
