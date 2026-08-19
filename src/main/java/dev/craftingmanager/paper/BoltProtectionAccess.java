package dev.craftingmanager.paper;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;
import java.util.UUID;

/** Honors Bolt (by pop4959) interact permission when the plugin is present. */
public final class BoltProtectionAccess implements ProtectionAccess {
    @Override public boolean canInteract(BlockKey block, UUID player) {
        if (Bukkit.getPluginManager().getPlugin("Bolt") == null) return true;
        try {
            Class<?> apiType = Class.forName("org.popcraft.bolt.BoltAPI");
            Object api = null;
            for (var provider : Bukkit.getServicesManager().getRegistrations(apiType)) {
                api = provider.getProvider();
                break;
            }
            if (api == null) return true;
            World world = Bukkit.getWorld(block.worldId());
            if (world == null) return true;
            Block bukkitBlock = world.getBlockAt(block.x(), block.y(), block.z());
            Method isProtected = apiType.getMethod("isProtected", Block.class);
            if (!(Boolean) isProtected.invoke(api, bukkitBlock)) return true;
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                Method canAccess = apiType.getMethod("canAccess", Block.class, Player.class, String[].class);
                return (Boolean) canAccess.invoke(api, bukkitBlock, online, new String[]{"interact"});
            }
            Method find = apiType.getMethod("findProtection", Block.class);
            Object protection = find.invoke(api, bukkitBlock);
            if (protection == null) return true;
            Class<?> protectionType = Class.forName("org.popcraft.bolt.protection.Protection");
            Method canAccess = apiType.getMethod("canAccess", protectionType, UUID.class, String[].class);
            return (Boolean) canAccess.invoke(api, protection, player, new String[]{"interact"});
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }
}
