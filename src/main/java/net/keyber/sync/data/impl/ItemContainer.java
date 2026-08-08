package net.keyber.sync.data.impl;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public record ItemContainer(int size, Map<Integer, byte[]> slots) {
    public ItemContainer {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    @Nullable
    public static ItemContainer of(ItemStack[] items) {
        if (items == null) {
            return null;
        }

        Map<Integer, byte[]> slots = new LinkedHashMap<>();

        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }

            slots.put(slot, item.serializeAsBytes());
        }

        return new ItemContainer(items.length, slots);
    }

    public static ItemContainer of(ItemStack item) {
        return of(new ItemStack[]{item});
    }

    public ItemStack[] toArray() {
        ItemStack[] items = new ItemStack[size];

        for (Map.Entry<Integer, byte[]> entry : slots.entrySet()) {
            int slot = entry.getKey();

            items[slot] = ItemStack.deserializeBytes(entry.getValue());
        }

        return items;
    }

    @Nullable
    public ItemStack toSingle() {
        ItemStack[] items = toArray();
        return items.length == 0 ? null : items[0];
    }
}
