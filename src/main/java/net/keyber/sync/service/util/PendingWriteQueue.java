package net.keyber.sync.service.util;

import net.keyber.sync.data.PlayerSnapshot;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PendingWriteQueue {
    private final int capacity;
    private final Map<UUID, PlayerSnapshot> queue = new LinkedHashMap<>();

    public PendingWriteQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void queue(PlayerSnapshot snapshot) {
        queue.put(snapshot.uuid(), snapshot);

        Iterator<UUID> oldest = queue.keySet().iterator();

        while (queue.size() > capacity && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    public synchronized void requeue(PlayerSnapshot snapshot) {
        queue.putIfAbsent(snapshot.uuid(), snapshot);
    }

    public synchronized List<PlayerSnapshot> drain() {
        if (queue.isEmpty()) {
            return List.of();
        }

        List<PlayerSnapshot> snapshots = List.copyOf(queue.values());
        queue.clear();

        return snapshots;
    }

    public synchronized int size() {
        return queue.size();
    }
}
