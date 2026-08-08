package net.keyber.sync.storage.redis;

import lombok.experimental.UtilityClass;
import net.keyber.sync.storage.credentials.RedisStorageCredentials;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

@UtilityClass
public class RedisManager {
    private final int TIMEOUT_MILLIS = 5000;
    private RedisClient CLIENT;

    public void init(RedisStorageCredentials storageCredentials) {
        if (CLIENT != null) {
            return;
        }

        ConnectionPoolConfig config = new ConnectionPoolConfig();

        config.setMaxWait(Duration.ofMillis(TIMEOUT_MILLIS));
        config.setMinIdle(storageCredentials.getMinIdle());
        config.setMaxTotal(storageCredentials.getMaxTotal());
        config.setTestOnBorrow(true);
        config.setBlockWhenExhausted(true);

        RedisClient client = RedisClient.builder()
                .poolConfig(config)
                .hostAndPort(storageCredentials.getAddress(), storageCredentials.getPort())
                .clientConfig(DefaultJedisClientConfig.builder()
                        .user(emptyToNull(storageCredentials.getUsername()))
                        .password(emptyToNull(storageCredentials.getPassword()))
                        .timeoutMillis(TIMEOUT_MILLIS)
                        .build())
                .build();

        try {
            client.ping();
        } catch (RuntimeException exception) {
            closeQuietly(client);

            throw new IllegalStateException(
                    "Could not connect to Redis at " + storageCredentials.getAddress() + ":"
                            + storageCredentials.getPort(),
                    exception);
        }

        CLIENT = client;
    }

    public RedisClient getClient() {
        return CLIENT;
    }

    public void close() {
        closeQuietly(CLIENT);
    }

    private void closeQuietly(RedisClient client) {
        if (client == null) {
            return;
        }

        try {
            client.close();
        } catch (RuntimeException ignored) {
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
