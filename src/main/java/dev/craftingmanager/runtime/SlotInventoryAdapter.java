package dev.craftingmanager.runtime;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemAccess;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.ItemVault;
import dev.craftingmanager.api.Reservation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SlotInventoryAdapter implements InventoryAdapter {
    private final ItemVault vault;

    public SlotInventoryAdapter(ItemVault vault) {
        this.vault = Objects.requireNonNull(vault);
    }

    @Override public boolean supports(BlockKey block, UUID owner) {
        return vault.of(owner).isPresent();
    }

    @Override public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
        throw new UnsupportedOperationException("owner-scoped capture is required");
    }

    @Override public List<Reservation.Claim> captureClaims(BlockKey block, UUID owner, List<ProcessInput> inputs) {
        ItemAccess access = vault.of(owner).orElse(null);
        if (access == null) return List.of();
        List<Reservation.Claim> claims = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (ProcessInput input : inputs) {
            if (input.optional() || input.timing() != InputTiming.ON_START) continue;
            int slot = find(access, input.matcher(), input.amount(), used);
            if (slot < 0) return List.of();
            used.add(slot);
            ItemSnapshot found = access.get(slot);
            claims.add(new Reservation.Claim(
                    Reservation.Source.PLAYER_INVENTORY, slot,
                    new ItemSnapshot(found.material(), found.amount(), found.metadata()),
                    input.amount(), input.id(), input.consumption()));
        }
        return List.copyOf(claims);
    }

    @Override public boolean claimsStillMatch(List<Reservation.Claim> claims) {
        throw new UnsupportedOperationException("owner-scoped match is required");
    }

    @Override public boolean claimsStillMatch(UUID owner, List<Reservation.Claim> claims) {
        ItemAccess access = vault.of(owner).orElse(null);
        if (access == null) return false;
        for (Reservation.Claim claim : claims) {
            ItemSnapshot current = access.get(claim.slot());
            if (current == null || !current.material().equals(claim.expected().material())) return false;
            if (current.amount() < claim.amount()) return false;
        }
        return true;
    }

    @Override public void remove(List<Reservation.Claim> claims) {
        throw new UnsupportedOperationException("owner-scoped remove is required");
    }

    @Override public void remove(UUID owner, List<Reservation.Claim> claims) {
        ItemAccess access = require(owner);
        for (Reservation.Claim claim : claims) take(access, claim);
    }

    @Override public void returnItems(List<Reservation.Claim> claims) {
        throw new UnsupportedOperationException("owner-scoped return is required");
    }

    @Override public void returnItems(UUID owner, List<Reservation.Claim> claims) {
        ItemAccess access = require(owner);
        for (Reservation.Claim claim : claims) {
            add(access, new ItemSnapshot(claim.expected().material(), claim.amount(), claim.expected().metadata()));
        }
    }

    public static void add(ItemAccess access, ItemSnapshot item) {
        for (int slot = 0; slot < access.size(); slot++) {
            ItemSnapshot current = access.get(slot);
            if (current != null && current.material().equals(item.material())
                    && current.metadata().equals(item.metadata())) {
                access.set(slot, new ItemSnapshot(current.material(), current.amount() + item.amount(), current.metadata()));
                return;
            }
        }
        for (int slot = 0; slot < access.size(); slot++) {
            if (access.get(slot) == null) {
                access.set(slot, item);
                return;
            }
        }
        throw new IllegalStateException("no empty slot for item output");
    }

    private ItemAccess require(UUID owner) {
        return vault.of(owner).orElseThrow(() -> new IllegalStateException("item vault missing owner " + owner));
    }

    private static void take(ItemAccess access, Reservation.Claim claim) {
        ItemSnapshot current = access.get(claim.slot());
        if (current == null || current.amount() < claim.amount()) {
            throw new IllegalStateException("claim no longer present");
        }
        int remaining = current.amount() - claim.amount();
        access.set(claim.slot(), remaining == 0 ? null : new ItemSnapshot(current.material(), remaining, current.metadata()));
    }

    private static int find(ItemAccess access, String matcher, int amount, Set<Integer> used) {
        for (int slot = 0; slot < access.size(); slot++) {
            if (used.contains(slot)) continue;
            ItemSnapshot current = access.get(slot);
            if (current != null && matcher.equals(current.material()) && current.amount() >= amount) return slot;
        }
        return -1;
    }
}
