package net.keyber.sync.data.impl;

public record InventoryData(
        ItemContainer main, ItemContainer armor, ItemContainer offHand, int heldSlot, ItemContainer enderChest) {
}
