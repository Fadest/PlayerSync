package net.keyber.sync.storage.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.Getter;
import net.keyber.sync.storage.credentials.MongoStorageCredentials;
import org.bson.Document;
import org.bson.UuidRepresentation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class MongoManager {
    private static final int TIMEOUT_MILLIS = 5000;

    @Getter
    private static volatile MongoManager instance;

    private final MongoClient client;

    @Getter
    private final MongoDatabase database;

    @Getter
    private final String collectionName;

    private final ExecutorService executor;

    private MongoManager(MongoStorageCredentials storageCredentials) {
        this.client = MongoClients.create(buildSettings(storageCredentials));
        this.database = client.getDatabase(storageCredentials.getDatabase());
        this.collectionName = storageCredentials.getCollection();
        this.executor = createExecutor(storageCredentials.getThreadPoolSize());
    }

    public static synchronized void init(MongoStorageCredentials storageCredentials) {
        if (instance != null) {
            return;
        }

        MongoManager manager = new MongoManager(storageCredentials);
        try {
            manager.database.runCommand(new Document("ping", 1));
        } catch (RuntimeException exception) {
            manager.closeDatabase();
            throw new IllegalStateException(
                    "Could not connect to MongoDB (" + storageCredentials.describe() + ")", exception);
        }

        instance = manager;
    }

    private static MongoClientSettings buildSettings(MongoStorageCredentials storageCredentials) {
        return MongoClientSettings.builder()
                .applicationName("PlayerSync")
                .applyToClusterSettings(
                        cluster -> cluster.serverSelectionTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                .applyConnectionString(parse(storageCredentials.getUri()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();
    }

    private static ConnectionString parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("'storage.mongo.uri' is missing from the configuration.");
        }

        try {
            return new ConnectionString(uri);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("The MongoDB uri is not valid", exception);
        }
    }

    private static ExecutorService createExecutor(int size) {
        AtomicInteger counter = new AtomicInteger();

        return Executors.newFixedThreadPool(Math.max(1, size), runnable -> {
            Thread thread = new Thread(runnable, "PlayerSync-Mongo-" + counter.incrementAndGet());
            // Daemon so that a stuck thread cannot keep the server from shutting down.
            // The drain already happens in closeDatabase().
            thread.setDaemon(true);

            return thread;
        });
    }

    public MongoCollection<Document> getPlayersCollection() {
        return database.getCollection(collectionName);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    public void runAsync(Runnable runnable) {
        CompletableFuture.runAsync(runnable, executor);
    }

    public void closeDatabase() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        client.close();
    }
}
