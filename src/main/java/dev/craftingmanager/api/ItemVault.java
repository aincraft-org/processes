package dev.craftingmanager.api;

import java.util.Optional;
import java.util.UUID;

public interface ItemVault {
    Optional<ItemAccess> of(UUID owner);
}
