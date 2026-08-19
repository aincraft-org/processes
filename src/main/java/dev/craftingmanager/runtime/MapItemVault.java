package dev.craftingmanager.runtime;

import dev.craftingmanager.api.ItemAccess;
import dev.craftingmanager.api.ItemVault;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MapItemVault implements ItemVault {
    private final Map<UUID, MapItemAccess> owners = new HashMap<>();

    public MapItemAccess open(UUID owner, int size) {
        return owners.compute(owner, (id, existing) -> existing == null ? new MapItemAccess(size) : existing);
    }

    @Override public Optional<ItemAccess> of(UUID owner) {
        return Optional.ofNullable(owners.get(owner));
    }
}
