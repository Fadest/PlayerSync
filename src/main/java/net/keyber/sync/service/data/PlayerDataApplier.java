package net.keyber.sync.service.data;

import lombok.RequiredArgsConstructor;
import net.keyber.sync.data.impl.AdvancementsData;
import net.keyber.sync.data.impl.EffectData;
import net.keyber.sync.data.impl.InventoryData;
import net.keyber.sync.data.impl.LocationData;
import net.keyber.sync.data.impl.LocationsData;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.data.impl.StateData;
import net.keyber.sync.service.SyncSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class PlayerDataApplier {
    private final SyncSettings settings;

    public void apply(Player player, PlayerSnapshot data) {
        if (data == null) {
            return;
        }

        if (data.state() != null && settings.isStateEnabled()) {
            applyState(player, data.state());
        }

        if (data.inventory() != null && settings.isInventoryEnabled()) {
            applyInventory(player, data.inventory());
        }

        if (data.statistics() != null && settings.isStatisticsEnabled()) {
            applyStatistics(player, data.statistics());
        }

        if (data.locations() != null && settings.isLocationsEnabled()) {
            applyLocations(player, data.locations());
        }

        if (data.advancements() != null && settings.isAdvancementsEnabled()) {
            applyAdvancements(player, data.advancements());
        }
    }

    private void applyState(Player player, StateData state) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && state.maxHealth() > 0.0D) {
            maxHealth.setBaseValue(state.maxHealth());
        }

        double limit = maxHealth == null ? 20.0D : maxHealth.getValue();
        player.setHealth(Math.min(state.health(), limit));

        player.setFoodLevel(state.foodLevel());
        player.setSaturation(state.saturation());
        player.setExhaustion(state.exhaustion());

        player.setLevel(state.level());
        player.setExp(state.experience());
        player.setTotalExperience(state.totalExperience());

        player.setGameMode(GameMode.valueOf(state.gameMode()));
        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.allowFlight() && state.flying());

        player.setWalkSpeed(state.walkSpeed());
        player.setFlySpeed(state.flySpeed());

        player.setFireTicks(state.fireTicks());
        player.setFallDistance(state.fallDistance());

        if (settings.isPotionEffectsEnabled()) {
            applyEffects(player, state);
        }
    }

    private void applyEffects(Player player, StateData state) {
        player.clearActivePotionEffects();

        if (state.effects() == null) {
            return;
        }

        for (EffectData effectData : state.effects()) {
            PotionEffect effect = effectData.toPotionEffect();
            if (effect == null) {
                continue;
            }

            player.addPotionEffect(effect);
        }
    }

    private void applyInventory(Player player, InventoryData inventory) {
        PlayerInventory playerInventory = player.getInventory();

        if (inventory.main() != null && settings.isMainInventoryEnabled()) {
            playerInventory.setStorageContents(inventory.main().toArray());
        }

        if (inventory.armor() != null && settings.isArmorEnabled()) {
            playerInventory.setArmorContents(inventory.armor().toArray());
        }

        if (inventory.offHand() != null && settings.isOffHandEnabled()) {
            playerInventory.setItemInOffHand(inventory.offHand().toSingle());
        }

        if (settings.isHeldSlotEnabled()) {
            playerInventory.setHeldItemSlot(inventory.heldSlot());
        }

        if (inventory.enderChest() != null && settings.isEnderChestEnabled()) {
            player.getEnderChest().setContents(inventory.enderChest().toArray());
        }
    }

    private void applyStatistics(Player player, Map<String, Integer> statistics) {
        for (Map.Entry<String, Integer> entry : statistics.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            if (key == null) {
                continue;
            }

            Statistic statistic = Registry.STATISTIC.get(key);
            if (statistic == null || statistic.getType() != Statistic.Type.UNTYPED) {
                continue;
            }

            player.setStatistic(statistic, entry.getValue());
        }
    }

    private void applyLocations(Player player, LocationsData locations) {
        LocationData respawnData = settings.filterIgnoredWorld(locations.respawn());
        if (settings.isRespawnLocationEnabled() && respawnData != null) {
            Location respawn = respawnData.toLocation();
            if (respawn != null) {
                player.setRespawnLocation(respawn);
            }
        }
    }

    private void applyAdvancements(Player player, AdvancementsData advancements) {
        Iterator<Advancement> iterator = Bukkit.advancementIterator();

        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            if (!settings.canSyncAdvancement(advancement.getKey())) {
                continue;
            }

            syncAdvancementCriteria(player, advancement, advancements.criteriaOf(advancement.getKey().toString()));
        }
    }

    private void syncAdvancementCriteria(Player player, Advancement advancement, Set<String> target) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        Collection<String> awardedCriteria = progress.getAwardedCriteria();

        for (String criterion : target) {
            if (!awardedCriteria.contains(criterion)) {
                progress.awardCriteria(criterion);
            }
        }
    }

    @Nullable
    public Location getLastLocation(Player player, PlayerSnapshot data) {
        if (!settings.isLocationsEnabled() || !settings.isApplyLocationOnJoin() || data.locations() == null) {
            return null;
        }

        LocationsData locations = data.locations();
        LocationData lastData = settings.filterIgnoredWorld(locations.last());

        if (lastData != null) {
            Location last = lastData.toLocation();

            if (last != null) {
                return last;
            }
        }

        if (!settings.isPerWorldLocationsEnabled() || locations.perWorld() == null) {
            return null;
        }

        LocationData fallback = settings.filterIgnoredWorld(locations.perWorld().get(player.getWorld().getUID()));
        return fallback == null ? null : fallback.toLocation();
    }
}
