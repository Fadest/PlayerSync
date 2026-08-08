package net.keyber.sync.data;

import net.keyber.sync.data.impl.*;

import java.util.Map;
import java.util.UUID;

public record PlayerSnapshot(
        UUID uuid,
        ProfileData profile,
        StateData state,
        LocationsData locations,
        InventoryData inventory,
        Map<String, Integer> statistics,
        AdvancementsData advancements) {

    public static PlayerSnapshot empty(UUID uuid) {
        return new PlayerSnapshot(uuid, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return profile == null;
    }
}
