package net.keyber.sync.data.impl;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record LocationData(UUID uid, double x, double y, double z, float yaw, float pitch) {
    @Nullable
    public static LocationData of(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        return new LocationData(
                location.getWorld().getUID(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(uid);
        if (world == null) {
            return null;
        }

        return new Location(world, x, y, z, yaw, pitch);
    }
}
