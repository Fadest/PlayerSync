package net.keyber.sync.service;

import net.keyber.sync.PlayerSync;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.event.PlayerDataSaveEvent;
import net.keyber.sync.service.util.PendingWriteQueue;
import net.keyber.sync.storage.PlayerRepository;
import net.keyber.sync.storage.mongo.MongoManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SyncTasks {
    private final PlayerSync plugin;
    private final SyncService service;
    private final PlayerRepository repository;
    private final PendingWriteQueue pendingWrites;
    private final SyncSettings settings;
    private final Logger logger;

    private int autoSaveCursor;
    private double autoSaveDebt;

    private BukkitTask autoSaveTask;
    private BukkitTask leaseRenewTask;
    private BukkitTask retryTask;

    protected SyncTasks(
            PlayerSync plugin,
            SyncService service,
            PlayerRepository repository,
            PendingWriteQueue pendingWrites,
            SyncSettings settings,
            Logger logger) {
        this.plugin = plugin;
        this.service = service;
        this.repository = repository;
        this.pendingWrites = pendingWrites;
        this.settings = settings;
        this.logger = logger;
    }

    protected void start() {
        if (settings.isAutoSaveEnabled()) {
            autoSaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoSaveTick, 20L, 20L);
        }

        if (settings.isRetryEnabled()) {
            long retryTicks = settings.getRetryIntervalSeconds() * 20L;

            retryTask = plugin.getServer()
                    .getScheduler()
                    .runTaskTimerAsynchronously(plugin, this::retryPendingWrites, retryTicks, retryTicks);
        }

        long renewTicks = Math.max(20L, settings.getLeaseRenewIntervalMillis() / 50L);
        leaseRenewTask = plugin.getServer()
                .getScheduler()
                .runTaskTimerAsynchronously(plugin, this::renewLeases, renewTicks, renewTicks);
    }

    protected void stop() {
        cancel(autoSaveTask);
        cancel(leaseRenewTask);
        cancel(retryTask);
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void autoSaveTick() {
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());

        if (online.isEmpty()) {
            autoSaveCursor = 0;
            autoSaveDebt = 0.0D;

            return;
        }

        autoSaveDebt += online.size() / (double) settings.getAutoSaveIntervalSeconds();

        int batch = (int) autoSaveDebt;
        if (batch <= 0) {
            return;
        }

        autoSaveDebt -= batch;

        for (int i = 0; i < batch; i++) {
            if (autoSaveCursor >= online.size()) {
                autoSaveCursor = 0;
            }

            autoSave(online.get(autoSaveCursor++));
        }
    }

    private void autoSave(Player player) {
        PlayerSnapshot snapshot = service.capture(player, PlayerDataSaveEvent.Cause.AUTO_SAVE);

        if (snapshot == null) {
            return;
        }

        MongoManager.getInstance().runAsync(() -> {
            try {
                repository.save(snapshot, false);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Periodic save failed for " + player.getName(), exception);
            }
        });
    }

    private void retryPendingWrites() {
        List<PlayerSnapshot> snapshots = pendingWrites.drain();

        if (snapshots.isEmpty()) {
            return;
        }

        try {
            int written = repository.saveAll(snapshots);
            logger.info("Retried pending writes: " + written + " of " + snapshots.size() + " written.");
        } catch (RuntimeException exception) {
            snapshots.forEach(pendingWrites::requeue);

            logger.log(
                    Level.WARNING,
                    "MongoDB is still unreachable and " + pendingWrites.size() + " writes are still pending.",
                    exception);
        }
    }

    private void renewLeases() {
        try {
            repository.renewAll(service.heldLockUuids());
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Failed to renew the session leases", exception);
        }
    }
}
