package net.keyber.sync.data.impl;

import java.util.Map;
import java.util.Set;

public record AdvancementsData(Map<String, Set<String>> awarded) {
    public AdvancementsData {
        awarded = awarded == null ? Map.of() : Map.copyOf(awarded);
    }

    public Set<String> criteriaOf(String advancementKey) {
        return awarded.getOrDefault(advancementKey, Set.of());
    }
}
