package net.keyber.sync.service.data;

import lombok.RequiredArgsConstructor;
import net.keyber.sync.data.impl.AdvancementsData;
import net.keyber.sync.data.impl.EffectData;
import net.keyber.sync.data.impl.InventoryData;
import net.keyber.sync.data.impl.ItemContainer;
import net.keyber.sync.data.impl.LocationData;
import net.keyber.sync.data.impl.LocationsData;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.data.impl.ProfileData;
import net.keyber.sync.data.impl.StateData;
import net.keyber.sync.service.SyncSettings;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
public class PlayerDataCapturer {
    private final SyncSettings settings;

    public PlayerSnapshot capture(Player player, PlayerSnapshot previous) {
        return new PlayerSnapshot(
                player.getUniqueId(),
                settings.isProfileEnabled() ? captureProfile(player, previous) : null,
                settings.isStateEnabled() ? captureState(player) : null,
                settings.isLocationsEnabled() ? captureLocations(player, previous) : null,
                settings.isInventoryEnabled() ? captureInventory(player) : null,
                settings.isStatisticsEnabled() ? captureStatistics(player) : null,
                settings.isAdvancementsEnabled() ? captureAdvancements(player) : null);
    }

    private ProfileData captureProfile(Player player, PlayerSnapshot previous) {
        return new ProfileData(
                player.getName(),
                firstSeen(previous),
                System.currentTimeMillis(),
                settings.getServerId());
    }

    private StateData captureState(Player player) {
        List<EffectData> effects = new ArrayList<>();

        if (settings.isPotionEffectsEnabled()) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                effects.add(EffectData.of(effect));
            }
        }

        return new StateData(
                player.getHealth(),
                getPlayerMaxHealth(player),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getGameMode().name(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                player.getFireTicks(),
                player.getFreezeTicks(),
                effects);
    }

    private LocationsData captureLocations(Player player, PlayerSnapshot previous) {
        boolean ignored = settings.isIgnoredWorld(player.getWorld());
        LocationData current = ignored ? null : LocationData.of(player.getLocation());

        return new LocationsData(
                ignored ? previousLast(previous) : current,
                settings.isRespawnLocationEnabled() ? settings.filterIgnoredWorld(LocationData.of(player.getRespawnLocation())) : null,
                capturePerWorld(previous, current));
    }


    private Map<UUID, LocationData> capturePerWorld(PlayerSnapshot previous, LocationData current) {
        if (!settings.isPerWorldLocationsEnabled()) {
            return Map.of();
        }

        Map<UUID, LocationData> perWorld = new LinkedHashMap<>();

        if (previous != null && previous.locations() != null && previous.locations().perWorld() != null) {
            for (Map.Entry<UUID, LocationData> entry : previous.locations().perWorld().entrySet()) {
                if (!settings.isIgnoredWorld(entry.getValue())) {
                    perWorld.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (current != null) {
            perWorld.put(current.uid(), current);
        }

        return perWorld;
    }

    private InventoryData captureInventory(Player player) {
        PlayerInventory inventory = player.getInventory();

        return new InventoryData(
                settings.isMainInventoryEnabled() ? ItemContainer.of(inventory.getStorageContents()) : null,
                settings.isArmorEnabled() ? ItemContainer.of(inventory.getArmorContents()) : null,
                settings.isOffHandEnabled() ? ItemContainer.of(inventory.getItemInOffHand()) : null,
                inventory.getHeldItemSlot(),
                settings.isEnderChestEnabled() ? ItemContainer.of(player.getEnderChest().getContents()) : null);
    }

    private Map<String, Integer> captureStatistics(Player player) {
        Map<String, Integer> statistics = new LinkedHashMap<>();

        for (Statistic statistic : settings.getTrackedStatistics()) {
            statistics.put(statistic.getKey().toString(), player.getStatistic(statistic));
        }

        return statistics;
    }

    private AdvancementsData captureAdvancements(Player player) {
        Iterator<Advancement> advancements = Bukkit.advancementIterator();

        Map<String, Set<String>> awarded = new LinkedHashMap<>();
        while (advancements.hasNext()) {
            Advancement advancement = advancements.next();

            if (!settings.canSyncAdvancement(advancement.getKey())) {
                continue;
            }

            Collection<String> criteria = player.getAdvancementProgress(advancement).getAwardedCriteria();
            if (criteria.isEmpty()) {
                continue;
            }

            awarded.put(advancement.getKey().toString(), Set.copyOf(criteria));
        }

        return new AdvancementsData(awarded);
    }

    private long firstSeen(PlayerSnapshot previous) {
        if (previous != null && previous.profile() != null && previous.profile().firstSeen() > 0L) {
            return previous.profile().firstSeen();
        }

        return System.currentTimeMillis();
    }

    private double getPlayerMaxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getBaseValue();
    }

    private LocationData previousLast(PlayerSnapshot previous) {
        if (previous == null || previous.locations() == null) {
            return null;
        }

        return settings.filterIgnoredWorld(previous.locations().last());
    }
}
