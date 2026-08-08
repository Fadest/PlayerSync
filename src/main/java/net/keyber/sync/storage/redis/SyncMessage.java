package net.keyber.sync.storage.redis;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SyncMessage(Type type, String originServerId, UUID uniqueId) {
    private static final String SEPARATOR = "|";

    public enum Type {
        LOCK_RELEASED,
        RELEASE_REQUEST
    }

    public String encode() {
        return type.name() + SEPARATOR + originServerId + SEPARATOR + uniqueId;
    }

    @Nullable
    public static SyncMessage decode(String raw) {
        if (raw == null) {
            return null;
        }

        String[] parts = raw.split("\\" + SEPARATOR, 3);

        if (parts.length < 3) {
            return null;
        }

        try {
            return new SyncMessage(Type.valueOf(parts[0]), parts[1], UUID.fromString(parts[2]));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
