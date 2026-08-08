package net.keyber.sync.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AdvancementListener implements Listener {
    public static final Set<UUID> APPLYING_ADVANCEMENTS = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (APPLYING_ADVANCEMENTS.contains(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }
}
