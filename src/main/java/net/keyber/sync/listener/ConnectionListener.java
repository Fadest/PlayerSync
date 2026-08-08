package net.keyber.sync.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import lombok.RequiredArgsConstructor;
import net.keyber.sync.service.SyncService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@RequiredArgsConstructor
public class ConnectionListener implements Listener {
    private final SyncService syncService;

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        SyncService.LoadOutcome outcome = syncService.loadAndLock(event.getUniqueId(), event.getName());

        switch (outcome.status()) {
            case LOCKED -> disallow(event, Component.text(
                            "Your data is still being saved on another server. Try again in a few seconds.",
                            NamedTextColor.RED));
            case ERROR -> disallow(event, Component.text("Your data could not be loaded.", NamedTextColor.RED));
        }
    }

    private void disallow(AsyncPlayerPreLoginEvent event, Component message) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        syncService.applyOnJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        syncService.saveOnQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        syncService.discardIfPending(event.getPlayerUniqueId());
    }
}
