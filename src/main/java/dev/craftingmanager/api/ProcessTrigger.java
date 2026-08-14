package dev.craftingmanager.api;

import dev.craftingmanager.api.Domain.BlockKey;

import java.util.Optional;
import java.util.UUID;

public interface ProcessTrigger {
    Optional<String> selectProcess(BlockKey block, UUID playerId);
}
