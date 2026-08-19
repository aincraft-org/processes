package dev.craftingmanager.paper;

import dev.craftingmanager.api.Domain.BlockKey;

import java.util.UUID;

/** Whether a player may start a process on a station block. */
public interface ProtectionAccess {
    boolean canInteract(BlockKey block, UUID player);

    static ProtectionAccess allowAll() {
        return (block, player) -> true;
    }
}
