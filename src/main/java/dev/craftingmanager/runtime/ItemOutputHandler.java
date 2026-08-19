package dev.craftingmanager.runtime;

import dev.craftingmanager.api.Domain.ItemOutput;
import dev.craftingmanager.api.EffectContext;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.ItemAccess;
import dev.craftingmanager.api.ItemVault;

import java.util.Objects;

public final class ItemOutputHandler implements EffectHandler<ItemOutput> {
    private final ItemVault vault;
    private final StationPorts stations;

    public ItemOutputHandler(ItemVault vault) {
        this(vault, null);
    }

    public ItemOutputHandler(ItemVault vault, StationPorts stations) {
        this.vault = Objects.requireNonNull(vault);
        this.stations = stations;
    }

    @Override public String type() { return ItemOutput.TYPE; }
    @Override public Class<ItemOutput> effectType() { return ItemOutput.class; }

    @Override public void execute(ItemOutput effect, String effectId) {
        throw new UnsupportedOperationException("item output requires effect context");
    }

    @Override public void execute(ItemOutput effect, EffectContext context) {
        if (stations != null && !effect.extractFaces().isEmpty()) {
            if (!stations.offerOutput(context.block(), effect)) {
                throw new IllegalStateException("station rejected item output " + effect.id());
            }
            return;
        }
        ItemAccess access = vault.of(context.owner())
                .orElseThrow(() -> new IllegalStateException("item vault missing owner " + context.owner()));
        SlotInventoryAdapter.add(access, effect.item());
    }
}
