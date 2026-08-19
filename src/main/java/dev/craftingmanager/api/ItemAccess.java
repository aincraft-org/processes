package dev.craftingmanager.api;

public interface ItemAccess {
    int size();
    ItemSnapshot get(int slot);
    void set(int slot, ItemSnapshot stack);
}
