package dev.craftingmanager.paper;

import dev.craftingmanager.api.CraftingManagerApi;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Registers Crafting Manager lockable station materials with Bolt when it is loaded. */
public final class BoltLockableHook {
    private final CraftingManagerApi api;
    private final Set<Material> added = new HashSet<>();
    private Map<Material, Object> protectable;

    public BoltLockableHook(CraftingManagerApi api) {
        this.api = api;
    }

    public void install() {
        protectable = protectableMap();
        if (protectable == null) return;
        register(api.lockableBlocks());
    }

    public void register(String material) {
        register(Set.of(material));
    }

    public void register(Collection<String> materials) {
        if (protectable == null) protectable = protectableMap();
        if (protectable == null) return;
        Object template = protectable.get(Material.BLAST_FURNACE);
        if (template == null) template = emptyConfig();
        for (String name : materials) {
            Material material = Material.matchMaterial(name);
            if (material == null || protectable.containsKey(material)) continue;
            if (BoltProtectableBlocks.registerMissing(protectable, template, Set.of(name)) > 0) {
                added.add(material);
            }
        }
    }

    public void uninstall() {
        if (protectable != null) BoltProtectableBlocks.remove(protectable, added);
        added.clear();
        protectable = null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Material, Object> protectableMap() {
        Plugin bolt = Bukkit.getPluginManager().getPlugin("Bolt");
        if (bolt == null) return null;
        try {
            Field field = bolt.getClass().getDeclaredField("protectableBlocks");
            field.setAccessible(true);
            Object value = field.get(bolt);
            if (value instanceof Map<?, ?> map) {
                return (Map<Material, Object>) map;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    private static Object emptyConfig() {
        try {
            Class<?> access = Class.forName("org.popcraft.bolt.access.Access");
            Class<?> config = Class.forName("org.popcraft.bolt.util.ProtectableConfig");
            Constructor<?> ctor = config.getDeclaredConstructor(access, boolean.class, boolean.class);
            return ctor.newInstance(null, false, false);
        } catch (ReflectiveOperationException ignored) {
            return new Object();
        }
    }
}
