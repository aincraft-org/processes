package dev.craftingmanager.paper;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Adds missing materials to Bolt's in-memory protectable map without editing Bolt's config. */
public final class BoltProtectableBlocks {
    private BoltProtectableBlocks() {}

    public static List<String> missing(Set<String> existing, Collection<String> materials) {
        List<String> result = new ArrayList<>();
        if (materials == null) return result;
        Set<String> present = existing == null ? Set.of() : existing;
        for (String name : materials) {
            if (name == null || name.isBlank()) continue;
            String id = name.toUpperCase(Locale.ROOT);
            if (!present.contains(id) && !result.contains(id)) result.add(id);
        }
        return result;
    }

    public static int registerMissing(Map<Material, Object> protectable, Object template, Collection<String> materials) {
        if (protectable == null) return 0;
        Set<String> existing = new HashSet<>();
        for (Material material : protectable.keySet()) existing.add(material.name());
        int added = 0;
        for (String name : missing(existing, materials)) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) continue;
            protectable.put(material, template);
            added++;
        }
        return added;
    }

    public static Set<Material> remove(Map<Material, Object> protectable, Collection<Material> added) {
        Set<Material> removed = new HashSet<>();
        if (protectable == null || added == null) return removed;
        for (Material material : added) {
            if (protectable.remove(material) != null) removed.add(material);
        }
        return removed;
    }
}
