package net.keyber.sync;

import net.keyber.sync.command.PlayerSyncCommand;
import net.keyber.sync.listener.AdvancementListener;
import net.keyber.sync.listener.ConnectionListener;
import net.keyber.sync.storage.credentials.MongoStorageCredentials;
import net.keyber.sync.storage.PlayerRepository;
import net.keyber.sync.storage.credentials.RedisStorageCredentials;
import net.keyber.sync.storage.mongo.MongoManager;
import net.keyber.sync.storage.redis.RedisManager;
import net.keyber.sync.storage.redis.RedisMessenger;
import net.keyber.sync.service.SyncService;
import net.keyber.sync.service.SyncSettings;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.RedisClient;

import java.util.List;
import java.util.logging.Level;

public class PlayerSync extends JavaPlugin {
    private SyncSettings syncSettings;
    private PlayerRepository playerRepository;
    private SyncService syncService;
    private RedisMessenger redisMessenger;
    private String redisChannel;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            initializeMongo();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "MongoDB is unavailable. Disabling the plugin", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            initializeRedis();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Redis is unavailable. Disabling the plugin", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        initializeSync();
    }

    @Override
    public void onDisable() {
        if (syncService != null) {
            syncService.stopTasks();
            syncService.saveAllShutdown();
        }

        if (redisMessenger != null) {
            redisMessenger.stop();
        }

        RedisManager.close();

        if (MongoManager.getInstance() != null) {
            MongoManager.getInstance().closeDatabase();
        }
    }

    private void initializeMongo() {
        ConfigurationSection section = section("storage.mongo");

        MongoManager.init(MongoStorageCredentials.builder()
                .uri(section.getString("uri", "mongodb://localhost:27017"))
                .database(section.getString("database", "playersync"))
                .collection(section.getString("collection", "players"))
                .threadPoolSize(section.getInt("thread-pool-size", 4))
                .build());
    }

    private void initializeRedis() {
        ConfigurationSection section = section("storage.redis");

        if (!section.getBoolean("enabled", true)) {
            return;
        }

        RedisManager.init(RedisStorageCredentials.builder()
                .address(section.getString("address", "localhost"))
                .port(section.getInt("port", 6379))
                .username(section.getString("username"))
                .password(section.getString("password"))
                .channel(section.getString("channel", "playersync"))
                .minIdle(section.getInt("min-idle", 1))
                .maxTotal(section.getInt("max-total", 16))
                .build());

        redisChannel = section.getString("channel", "playersync");
    }

    private void initializeSync() {
        syncSettings = SyncSettings.from(getConfig().getConfigurationSection("sync"),
                "server-" + getServer().getPort()
        );

        playerRepository = new PlayerRepository(
                MongoManager.getInstance().getPlayersCollection(), syncSettings, getLogger());
        playerRepository.ensureIndexes();

        RedisClient client = RedisManager.getClient();
        if (client != null) {
            redisMessenger = new RedisMessenger(RedisManager.getClient(), redisChannel,
                    syncSettings.getServerId(), getLogger());
            redisMessenger.start();
        }

        syncService = new SyncService(this, playerRepository, syncSettings, redisMessenger, getLogger());
        syncService.startTasks();

        getServer().getPluginManager().registerEvents(new ConnectionListener(syncService), this);

        if (syncSettings.isAdvancementsEnabled()) {
            getServer().getPluginManager().registerEvents(new AdvancementListener(), this);
        }

        registerCommand();
    }

    private void registerCommand() {
        PlayerSyncCommand command = new PlayerSyncCommand(syncService, playerRepository, syncSettings, getLogger());

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "Player data synchronisation admin commands.", List.of("psync")));
    }

    private ConfigurationSection section(String path) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);

        return section == null ? new MemoryConfiguration() : section;
    }
}
