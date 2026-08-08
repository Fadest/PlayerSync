package net.keyber.sync.storage.redis;

import lombok.Setter;
import net.keyber.sync.service.util.LockWaitRegistry;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RedisMessenger {
    private static final long INITIAL_HOLD_MILLIS = 1000L;

    private final RedisClient client;
    private final String channel;
    private final String serverId;
    private final Logger logger;
    private final LockWaitRegistry lockWaitRegistry = new LockWaitRegistry();
    private final AtomicLong hold = new AtomicLong(INITIAL_HOLD_MILLIS);

    private volatile boolean running;
    private volatile JedisPubSub subscriber;
    @Setter
    private volatile Consumer<UUID> releaseRequestHandler;
    private Thread subscriberThread;

    public RedisMessenger(RedisClient client, String channel, String serverId, Logger logger) {
        this.client = client;
        this.channel = channel;
        this.serverId = serverId;
        this.logger = logger;
    }

    public void start() {
        running = true;

        subscriberThread = new Thread(this::subscribeLoop, "PlayerSync-Redis-Sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void stop() {
        running = false;
        lockWaitRegistry.signalAll();

        JedisPubSub current = subscriber;

        if (current != null) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
            }
        }

        subscriberThread.interrupt();

        try {
            subscriberThread.join(2000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public LockWaitRegistry.Watch watch(UUID playerId) {
        return lockWaitRegistry.watch(playerId);
    }

    public void publishReleaseRequest(UUID playerId) {
        publish(SyncMessage.Type.RELEASE_REQUEST, playerId);
    }

    public void publishLockReleased(UUID playerId) {
        publish(SyncMessage.Type.LOCK_RELEASED, playerId);
    }

    private void publish(SyncMessage.Type type, UUID playerId) {
        try {
            client.publish(channel, new SyncMessage(type, serverId, playerId).encode());
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not publish message " + type + " for " + playerId, exception);
        }
    }

    public void publishLockReleased(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return;
        }

        try {
            AbstractPipeline pipeline = client.pipelined();

            for (UUID uniqueId : playerIds) {
                pipeline.publish(channel, new SyncMessage(SyncMessage.Type.LOCK_RELEASED, serverId, uniqueId).encode());
            }

            pipeline.sync();
        } catch (RuntimeException exception) {
            logger.log(Level.FINE, "Could not publish the lock-released notices", exception);
        }
    }

    private void subscribeLoop() {
        while (running) {
            JedisPubSub current = createSubscriber();
            subscriber = current;

            try {
                client.subscribe(current, channel);
            } catch (RuntimeException exception) {
                if (!running) {
                    return;
                }

                long delay = hold.get();

                logger.log(Level.WARNING, "Redis Pub/Sub connection has been lost. Retrying in " + delay + " ms.", exception);

                // Pause before trying to subscribe() again
                if (!sleepWithoutException(delay)) {
                    return;
                }

                hold.set(Math.min(delay * 2L, 30_000L));
                continue;
            }

            if (!running) {
                return;
            }

            // Pause before trying to subscribe() again if something failed without exceptions
            if (!sleepWithoutException(1000L)) {
                return;
            }
        }
    }

    private JedisPubSub createSubscriber() {
        return new JedisPubSub() {
            @Override
            public void onSubscribe(String subscribedChannel, int subscribedChannels) {
                hold.set(INITIAL_HOLD_MILLIS);
            }

            @Override
            public void onMessage(String messageChannel, String message) {
                handle(message);
            }

            private void handle(String raw) {
                try {
                    SyncMessage message = SyncMessage.decode(raw);

                    if (message == null || serverId.equals(message.originServerId())) {
                        return;
                    }

                    switch (message.type()) {
                        case LOCK_RELEASED -> lockWaitRegistry.signal(message.uniqueId());
                        case RELEASE_REQUEST -> {
                            Consumer<UUID> handler = releaseRequestHandler;

                            if (handler != null) {
                                handler.accept(message.uniqueId());
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    logger.log(Level.WARNING, "Failure to handle Redis message", exception);
                }
            }
        };
    }

    private boolean sleepWithoutException(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
