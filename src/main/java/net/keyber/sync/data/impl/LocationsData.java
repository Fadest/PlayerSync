package net.keyber.sync.data.impl;

import java.util.Map;
import java.util.UUID;

public record LocationsData(LocationData last, LocationData respawn, Map<UUID, LocationData> perWorld) {
}
