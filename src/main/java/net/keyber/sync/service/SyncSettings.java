package net.keyber.sync.service;

import lombok.Getter;
import net.keyber.sync.data.impl.LocationData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.*;

@Getter
public class SyncSettings {

    private static final List<String> DEFAULT_STATISTICS = List.of(
            "PLAY_ONE_MINUTE", "TOTAL_WORLD_TIME", "TIME_SINCE_DEATH", "TIME_SINCE_REST",
            "DEATHS", "MOB_KILLS", "PLAYER_KILLS", "DAMAGE_DEALT", "DAMAGE_TAKEN",
            "JUMP", "WALK_ONE_CM", "SPRINT_ONE_CM", "SWIM_ONE_CM", "FALL_ONE_CM",
            "FLY_ONE_CM", "LEAVE_GAME", "FISH_CAUGHT", "ANIMALS_BRED", "ITEM_ENCHANTED",
            "RAID_WIN", "TRADED_WITH_VILLAGER");

    private final String serverId;

    private final boolean profileEnabled;
    private final boolean stateEnabled;
    private final boolean potionEffectsEnabled;
    private final boolean locationsEnabled;
    private final boolean applyLocationOnJoin;
    private final boolean perWorldLocationsEnabled;
    private final boolean respawnLocationEnabled;

    private final Set<String> ignoredWorlds;

    private final boolean inventoryEnabled;
    private final boolean mainInventoryEnabled;
    private final boolean armorEnabled;
    private final boolean offHandEnabled;
    private final boolean heldSlotEnabled;
    private final boolean enderChestEnabled;

    private final boolean statisticsEnabled;
    private final Set<Statistic> trackedStatistics;

    private final boolean advancementsEnabled;

    private final boolean autoSaveEnabled;
    private final int autoSaveIntervalSeconds;

    private final boolean retryEnabled;
    private final int retryIntervalSeconds;
    private final int maxPendingWrites;

    private final long leaseAcquireTimeoutMillis;
    private final long leaseDurationMillis;

    private SyncSettings(ConfigurationSection section, String defaultServerId) {
        String configuredServerId = section.getString("server-id");

        boolean serverIdConfigured = configuredServerId != null && !configuredServerId.isBlank();
        this.serverId = serverIdConfigured ? configuredServerId : defaultServerId;

        this.profileEnabled = section.getBoolean("fields.profile.enabled", true);

        this.stateEnabled = section.getBoolean("fields.state.enabled", true);
        this.potionEffectsEnabled = section.getBoolean("fields.state.potion-effects", true);

        this.locationsEnabled = section.getBoolean("fields.locations.enabled", true);
        this.applyLocationOnJoin = section.getBoolean("fields.locations.apply-on-join", true);
        this.perWorldLocationsEnabled = section.getBoolean("fields.locations.per-world", true);
        this.respawnLocationEnabled = section.getBoolean("fields.locations.respawn", true);
        this.ignoredWorlds = Set.copyOf(section.getStringList("fields.locations.ignored-worlds"));

        this.inventoryEnabled = section.getBoolean("fields.inventory.enabled", true);
        this.mainInventoryEnabled = section.getBoolean("fields.inventory.main", true);
        this.armorEnabled = section.getBoolean("fields.inventory.armor", true);
        this.offHandEnabled = section.getBoolean("fields.inventory.off-hand", true);
        this.heldSlotEnabled = section.getBoolean("fields.inventory.held-slot", true);
        this.enderChestEnabled = section.getBoolean("fields.inventory.ender-chest", true);

        this.statisticsEnabled = section.getBoolean("fields.statistics.enabled", true);
        this.trackedStatistics = resolveStatistics(
                section.getStringList("fields.statistics.tracked"));

        this.advancementsEnabled = section.getBoolean("fields.advancements.enabled", true);

        this.autoSaveEnabled = section.getBoolean("auto-save.enabled", true);
        this.autoSaveIntervalSeconds = Math.max(30, section.getInt("auto-save.interval-seconds", 300));

        this.retryEnabled = section.getBoolean("retry.enabled", true);
        this.retryIntervalSeconds = Math.max(5, section.getInt("retry.interval-seconds", 30));
        this.maxPendingWrites = Math.max(1, section.getInt("retry.max-pending", 500));

        this.leaseAcquireTimeoutMillis = Math.max(1000L, section.getLong("lease-acquire-timeout-ms", 5000L));
        this.leaseDurationMillis = Math.max(10_000L, section.getLong("lease-duration-ms", 60_000L));
    }

    public long getLeaseRenewIntervalMillis() {
        return leaseDurationMillis / 3L;
    }

    public boolean isIgnoredWorld(World world) {
        return world != null && ignoredWorlds.contains(world.getName());
    }

    public boolean isIgnoredWorld(LocationData location) {
        return location != null && isIgnoredWorld(Bukkit.getWorld(location.uid()));
    }

    public LocationData filterIgnoredWorld(LocationData location) {
        return isIgnoredWorld(location) ? null : location;
    }

    public boolean canSyncAdvancement(NamespacedKey key) {
        return !key.getKey().startsWith("recipes/");
    }

    public static SyncSettings from(ConfigurationSection section, String defaultServerId) {
        return new SyncSettings(section == null ? emptySection() : section, defaultServerId);
    }

    private static ConfigurationSection emptySection() {
        return new MemoryConfiguration();
    }

    private static Set<Statistic> resolveStatistics(List<String> names) {
        List<String> source = names == null || names.isEmpty() ? DEFAULT_STATISTICS : names;
        Set<Statistic> resolved = new LinkedHashSet<>();

        for (String name : source) {
            Statistic statistic;

            try {
                statistic = Statistic.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (IllegalArgumentException exception) {
                continue;
            }

            if (statistic.getType() != Statistic.Type.UNTYPED) {
                continue;
            }

            resolved.add(statistic);
        }

        return Set.copyOf(resolved);
    }
}
