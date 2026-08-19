package dev.craftingmanager.api;

import dev.craftingmanager.api.Domain.BlockKey;

import java.util.UUID;

public record EffectContext(UUID instanceId, long revision, UUID owner, BlockKey block, String effectId) {
    public EffectContext {
        if (instanceId == null || owner == null || block == null || effectId == null || effectId.isBlank()) {
            throw new IllegalArgumentException("effect context identity is required");
        }
    }
}
