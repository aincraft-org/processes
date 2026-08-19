package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.SlotInventoryAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SlotInventoryAdapterTest {
    @Test void capturesAndRemovesMatchingSlots() {
        UUID owner = UUID.randomUUID();
        MapItemVault vault = new MapItemVault();
        vault.open(owner, 9).set(2, new ItemSnapshot("IRON_INGOT", 3, null));
        vault.open(owner, 9).set(5, new ItemSnapshot("COAL", 1, null));
        SlotInventoryAdapter adapter = new SlotInventoryAdapter(vault);
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 0, 0);
        List<Reservation.Claim> claims = adapter.captureClaims(block, owner, List.of(
                input("iron", "IRON_INGOT", 1),
                input("fuel", "COAL", 1)));
        assertEquals(2, claims.size());
        assertTrue(adapter.claimsStillMatch(owner, claims));
        adapter.remove(owner, claims);
        assertEquals(2, vault.open(owner, 9).get(2).amount());
        assertNull(vault.open(owner, 9).get(5));
    }

    @Test void captureFailsClosedWhenAnInputIsMissing() {
        UUID owner = UUID.randomUUID();
        MapItemVault vault = new MapItemVault();
        vault.open(owner, 9).set(0, new ItemSnapshot("IRON_INGOT", 1, null));
        SlotInventoryAdapter adapter = new SlotInventoryAdapter(vault);
        List<Reservation.Claim> claims = adapter.captureClaims(
                new BlockKey(UUID.randomUUID(), 0, 0, 0), owner,
                List.of(input("iron", "IRON_INGOT", 1), input("fuel", "COAL", 1)));
        assertTrue(claims.isEmpty());
    }

    private static ProcessInput input(String id, String matcher, int amount) {
        return new ProcessInput(id, InputRole.PRIMARY_MATERIAL, matcher, amount,
                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null);
    }
}
