package dev.craftingmanager.runtime;

import dev.craftingmanager.api.ItemAccess;
import dev.craftingmanager.api.ItemSnapshot;

public final class MapItemAccess implements ItemAccess {
    private final ItemSnapshot[] slots;

    public MapItemAccess(int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        this.slots = new ItemSnapshot[size];
    }

    @Override public int size() { return slots.length; }

    @Override public ItemSnapshot get(int slot) {
        check(slot);
        return slots[slot];
    }

    @Override public void set(int slot, ItemSnapshot stack) {
        check(slot);
        slots[slot] = stack;
    }

    private void check(int slot) {
        if (slot < 0 || slot >= slots.length) throw new IllegalArgumentException("slot out of range");
    }
}
