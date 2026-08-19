package dev.craftingmanager.runtime;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ItemOutput;

public interface StationPorts {
    boolean offerOutput(BlockKey block, ItemOutput output);
}
