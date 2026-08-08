package net.keyber.sync.service.util;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LockWaitRegistry {
    private final Map<UUID, Set<CountDownLatch>> latchesByPlayer = new ConcurrentHashMap<>();

    public Watch watch(UUID playerId) {
        CountDownLatch latch = new CountDownLatch(1);

        latchesByPlayer
                .computeIfAbsent(playerId, key -> ConcurrentHashMap.newKeySet())
                .add(latch);
        return new Watch(playerId, latch);
    }

    public void signal(UUID playerId) {
        Set<CountDownLatch> latches = latchesByPlayer.get(playerId);

        if (latches != null) {
            latches.forEach(CountDownLatch::countDown);
        }
    }

    public void signalAll() {
        latchesByPlayer.values().forEach(latches -> latches.forEach(CountDownLatch::countDown));
    }

    public class Watch implements AutoCloseable {
        private final UUID playerId;
        private final CountDownLatch latch;

        private Watch(UUID playerId, CountDownLatch latch) {
            this.playerId = playerId;
            this.latch = latch;
        }

        public void await(long millis) {
            try {
                latch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            latchesByPlayer.computeIfPresent(playerId, (key, latches) -> {
                latches.remove(latch);
                return latches.isEmpty() ? null : latches;
            });
        }
    }
}
