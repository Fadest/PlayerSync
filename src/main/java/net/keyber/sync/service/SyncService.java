package net.keyber.sync.service;

import net.keyber.sync.PlayerSync;
import net.keyber.sync.event.PlayerDataApplyEvent;
import net.keyber.sync.event.PlayerDataLoadEvent;
import net.keyber.sync.event.PlayerDataSaveEvent;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.listener.AdvancementListener;
import net.keyber.sync.service.data.PlayerDataApplier;
import net.keyber.sync.service.data.PlayerDataCapturer;
import net.keyber.sync.service.util.PendingWriteQueue;
import net.keyber.sync.storage.PlayerRepository;
import net.keyber.sync.storage.mongo.MongoManager;
import net.keyber.sync.service.util.LockWaitRegistry;
import net.keyber.sync.storage.redis.RedisMessenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SyncService {

    private static final long LOCK_RETRY_DELAY_MILLIS = 250L;
    private static final long TRANSFER_REACQUIRE_TICKS = 200L;

    private static final int SHUTDOWN_WRITE_ATTEMPTS = 3;
    private static final long SHUTDOWN_RETRY_DELAY_MILLIS = 1000L;

    private final PlayerSync plugin;
    private final PlayerRepository repository;
    private final PlayerDataCapturer capturer;
    private final PlayerDataApplier applier;
    private final SyncSettings settings;
    private final Logger logger;

    @Nullable
    private final RedisMessenger messenger;

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final PendingWriteQueue pendingWrites;
    private final SyncTasks tasks;

    public SyncService(PlayerSync plugin, PlayerRepository repository, SyncSettings settings, @Nullable RedisMessenger messenger, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
        this.messenger = messenger;
        this.logger = logger;
        this.capturer = new PlayerDataCapturer(settings);
        this.applier = new PlayerDataApplier(settings);
        this.pendingWrites = new PendingWriteQueue(settings.getMaxPendingWrites());
        this.tasks = new SyncTasks(plugin, this, repository, pendingWrites, settings, logger);

        if (messenger != null) {
            messenger.setReleaseRequestHandler(this::onReleaseRequested);
        }
    }

    public void startTasks() {
        tasks.start();
    }

    public void stopTasks() {
        tasks.stop();
    }

    public LoadOutcome loadAndLock(UUID uuid, String name) {
        long deadline = System.currentTimeMillis() + settings.getLeaseAcquireTimeoutMillis();
        String holder = null;
        boolean releaseRequested = false;

        try {
            while (true) {
                try (LockWaitRegistry.Watch watch = watch(uuid)) {
                    PlayerRepository.AcquireResult result = repository.acquire(uuid);

                    if (result.acquired()) {
                        PlayerSnapshot snapshot = result.snapshot();
                        PlayerDataLoadEvent event = new PlayerDataLoadEvent(uuid, name, snapshot);
                        event.callEvent();

                        snapshot = event.getSnapshot();

                        sessions.put(uuid, new PlayerSession(PlayerSession.State.PENDING_LOGIN, snapshot));
                        return new LoadOutcome(LoadStatus.SUCCESS, snapshot, null);
                    }

                    holder = result.lockedByServer();

                    if (!releaseRequested && messenger != null) {
                        messenger.publishReleaseRequest(uuid);
                        releaseRequested = true;
                    }

                    if (System.currentTimeMillis() >= deadline) {
                        return new LoadOutcome(LoadStatus.LOCKED, null, holder);
                    }

                    long slice = Math.min(LOCK_RETRY_DELAY_MILLIS, deadline - System.currentTimeMillis());

                    if (watch == null) {
                        Thread.sleep(Math.max(1L, slice));
                    } else {
                        watch.await(Math.max(1L, slice));
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new LoadOutcome(LoadStatus.LOCKED, null, holder);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to load data for " + name, exception);
            return new LoadOutcome(LoadStatus.ERROR, null, exception.getMessage());
        }
    }

    public void discardIfPending(UUID uuid) {
        if (removeIfInState(uuid, PlayerSession.State.PENDING_LOGIN) == null) {
            return;
        }

        MongoManager.getInstance().runAsync(() -> {
            try {
                repository.release(uuid);
                announceRelease(uuid);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Failed to release the lock for " + uuid, exception);
            }
        });
    }

    private void onReleaseRequested(UUID uuid) {
        plugin.getServer().getScheduler().runTask(plugin, () -> transfer(uuid));
    }

    private void transfer(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        PlayerSession session = sessions.get(uuid);

        if (player == null || !player.isOnline() || session == null || !session.is(PlayerSession.State.MANAGED)) {
            return;
        }

        PlayerSnapshot snapshot = capture(player, PlayerDataSaveEvent.Cause.TRANSFER);
        if (snapshot == null) {
            return;
        }

        sessions.computeIfPresent(uuid, (key, current) ->
                current.withState(PlayerSession.State.TRANSFERRED));

        MongoManager.getInstance().runAsync(() -> {
            try {
                repository.save(snapshot, true);
            } catch (RuntimeException exception) {
                if (settings.isRetryEnabled()) {
                    pendingWrites.queue(snapshot);
                }

                logger.log(Level.SEVERE, "Failed to save " + player.getName() + " while transferring to another server.", exception);
            } finally {
                announceRelease(uuid);
            }
        });

        scheduleTransferReacquire(uuid);
    }

    private void scheduleTransferReacquire(UUID uuid) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PlayerSession session = sessions.get(uuid);

            if (session == null || !session.is(PlayerSession.State.TRANSFERRED)) {
                return;
            }

            Player player = plugin.getServer().getPlayer(uuid);

            if (player == null || !player.isOnline()) {
                sessions.remove(uuid);
                return;
            }

            MongoManager.getInstance().runAsync(() -> {
                try {
                    if (repository.acquire(uuid).acquired()) {
                        sessions.computeIfPresent(uuid, (key, current) -> current.withState(PlayerSession.State.MANAGED));
                    }
                } catch (RuntimeException exception) {
                    logger.log(Level.WARNING, "Failed to take back the lock for " + player.getName(), exception);
                }
            });
        }, TRANSFER_REACQUIRE_TICKS);
    }

    private LockWaitRegistry.Watch watch(UUID uuid) {
        return messenger == null ? null : messenger.watch(uuid);
    }

    private void announceRelease(UUID uuid) {
        if (messenger != null) {
            messenger.publishLockReleased(uuid);
        }
    }

    public enum LoadStatus {
        SUCCESS,
        LOCKED,
        ERROR
    }

    public record LoadOutcome(LoadStatus status, PlayerSnapshot data, String detail) {
    }

    public void applyOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = sessions.get(uuid);

        if (session == null || !session.is(PlayerSession.State.PENDING_LOGIN)) {
            return;
        }

        PlayerSnapshot snapshot = session.snapshot();
        PlayerDataApplyEvent event = new PlayerDataApplyEvent(player, snapshot);
        event.callEvent();

        snapshot = event.getSnapshot();

        AdvancementListener.APPLYING_ADVANCEMENTS.add(uuid);

        try {
            applier.apply(player, snapshot);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to apply data to " + player.getName() + ", their session will not be managed.", exception);
            sessions.remove(uuid);

            MongoManager.getInstance().runAsync(() -> {
                try {
                    repository.release(uuid);
                } finally {
                    announceRelease(uuid);
                }
            });
            return;
        } finally {
            AdvancementListener.APPLYING_ADVANCEMENTS.remove(uuid);
        }

        sessions.computeIfPresent(uuid, (key, current) ->
                current.withState(PlayerSession.State.MANAGED));

        teleportOnJoin(player, snapshot);
    }

    private void teleportOnJoin(Player player, PlayerSnapshot data) {
        Location target = applier.getLastLocation(player, data);
        if (target == null) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        });
    }

    public void saveOnQuit(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSnapshot snapshot = capture(player, PlayerDataSaveEvent.Cause.DISCONNECT);
        PlayerSession session = sessions.remove(uuid);
        if (snapshot == null || session == null) {
            return;
        }

        if (session.is(PlayerSession.State.TRANSFERRED)) {
            return;
        }

        if (snapshot.isEmpty()) {
            MongoManager.getInstance().runAsync(() -> {
                try {
                    repository.release(uuid);
                } finally {
                    announceRelease(uuid);
                }
            });
            return;
        }

        MongoManager.getInstance().runAsync(() -> {
            try {
                repository.save(snapshot, true);
            } catch (RuntimeException exception) {
                if (settings.isRetryEnabled()) {
                    pendingWrites.queue(snapshot);
                }
            } finally {
                announceRelease(uuid);
            }
        });
    }

    public CompletableFuture<Boolean> saveNow(Player player) {
        PlayerSnapshot snapshot = capture(player, PlayerDataSaveEvent.Cause.AUTO_SAVE);

        if (snapshot == null) {
            return CompletableFuture.completedFuture(false);
        }

        return MongoManager.getInstance().supplyAsync(() -> repository.save(snapshot, false));
    }

    public void saveAllOnline(IntConsumer onComplete) {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());

        if (players.isEmpty()) {
            onComplete.accept(0);

            return;
        }

        new BukkitRunnable() {
            private int index;
            private int saved;

            @Override
            public void run() {
                for (int i = 0; i < 5 && index < players.size(); i++) {
                    Player player = players.get(index++);
                    if (player.isOnline()) {
                        saveNow(player);
                        saved++;
                    }
                }

                if (index >= players.size()) {
                    cancel();
                    onComplete.accept(saved);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void saveAllShutdown() {
        List<PlayerSnapshot> snapshots = new ArrayList<>();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                PlayerSnapshot snapshot = capture(player, PlayerDataSaveEvent.Cause.SHUTDOWN);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, "Failed to capture data for " + player.getName(), exception);
            }
        }

        snapshots.addAll(pendingWrites.drain());
        writeOnShutdown(snapshots);

        if (messenger != null) {
            messenger.publishLockReleased(snapshots.stream().map(PlayerSnapshot::uuid).toList());
        }

        sessions.clear();
    }

    protected List<UUID> heldLockUuids() {
        return sessions.entrySet().stream()
                .filter(entry -> !entry.getValue().is(PlayerSession.State.TRANSFERRED))
                .map(Map.Entry::getKey)
                .toList();
    }

    protected PlayerSnapshot capture(Player player, PlayerDataSaveEvent.Cause cause) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = sessions.get(uuid);

        if (session == null) {
            return null;
        }

        if (session.is(PlayerSession.State.TRANSFERRED) && cause != PlayerDataSaveEvent.Cause.TRANSFER) {
            return null;
        }

        PlayerSnapshot snapshot = capturer.capture(player, session.snapshot());
        PlayerDataSaveEvent event = new PlayerDataSaveEvent(player, snapshot, cause);
        if (!event.callEvent()) {
            return null;
        }

        PlayerSnapshot captured = event.getSnapshot();
        sessions.computeIfPresent(uuid, (key, current) -> current.withSnapshot(captured));
        return captured;
    }

    private PlayerSession removeIfInState(UUID uuid, PlayerSession.State state) {
        AtomicReference<PlayerSession> removed = new AtomicReference<>();

        sessions.computeIfPresent(uuid, (key, session) -> {
            if (!session.is(state)) {
                return session;
            }

            removed.set(session);
            return null;
        });

        return removed.get();
    }

    private void writeOnShutdown(List<PlayerSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }

        for (int attempt = 1; attempt <= SHUTDOWN_WRITE_ATTEMPTS; attempt++) {
            try {
                repository.saveAll(snapshots);
                return;
            } catch (RuntimeException exception) {
                if (attempt == SHUTDOWN_WRITE_ATTEMPTS) {
                    logger.log(Level.SEVERE, "Could not save the data of " + snapshots.size() + " player(s) during shutdown.", exception);
                    return;
                }

                logger.warning("Save on shutdown failed, retrying...");

                try {
                    Thread.sleep(SHUTDOWN_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
