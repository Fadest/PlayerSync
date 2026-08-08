package net.keyber.sync.storage;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.data.PlayerDataCodec;
import net.keyber.sync.service.SyncSettings;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerRepository {

    private static final String FIELD_ID = "_id";
    private static final String FIELD_LOCK = "lock";
    private static final String FIELD_LOCK_SERVER = "lock.server";
    private static final String FIELD_LOCK_RENEWED_AT = "lock.renewedAt";

    private final MongoCollection<Document> collection;
    private final SyncSettings settings;
    private final Logger logger;

    public PlayerRepository(MongoCollection<Document> collection, SyncSettings settings, Logger logger) {
        this.collection = collection;
        this.settings = settings;
        this.logger = logger;
    }

    public record AcquireResult(@NotNull PlayerSnapshot snapshot, @Nullable String lockedByServer) {
        public boolean acquired() {
            return lockedByServer == null;
        }
    }

    public void ensureIndexes() {
        try {
            collection.createIndex(Indexes.ascending("profile.name"), new IndexOptions().background(true).sparse(true));
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not create the index on profile.name", exception);
        }
    }

    public AcquireResult acquire(UUID uniqueId) {
        long now = System.currentTimeMillis();
        long staleBefore = now - settings.getLeaseDurationMillis();

        Bson filter = Filters.and(
                Filters.eq(FIELD_ID, uniqueId),
                Filters.or(
                        Filters.eq(FIELD_LOCK, null),
                        Filters.eq(FIELD_LOCK_SERVER, settings.getServerId()),
                        Filters.lt(FIELD_LOCK_RENEWED_AT, staleBefore)));

        Bson update = Updates.set(FIELD_LOCK, new Document("server", settings.getServerId())
                .append("acquiredAt", now)
                .append("renewedAt", now));

        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);

        try {
            Document document = collection.findOneAndUpdate(filter, update, options);

            return new AcquireResult(Objects.requireNonNullElse(PlayerDataCodec.decode(document), PlayerSnapshot.empty(uniqueId)), null);
        } catch (MongoCommandException | MongoWriteException exception) {
            if (!isDuplicateKey(exception)) {
                throw exception;
            }

            // Document exists but is already held by another server.
            // That's because Mongo tried to do update and collided with _id
            return new AcquireResult(PlayerSnapshot.empty(uniqueId), holderOf(uniqueId));
        }
    }

    private boolean isDuplicateKey(RuntimeException exception) {
        if (exception instanceof MongoWriteException writeException) {
            return writeException.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        }

        return exception instanceof MongoCommandException commandException && commandException.getErrorCode() == 11000;
    }

    private String holderOf(UUID uuid) {
        try {
            Document document = collection.find(Filters.eq(FIELD_ID, uuid))
                    .projection(new Document(FIELD_LOCK, 1))
                    .first();

            if (document != null) {
                Document lock = document.get(FIELD_LOCK, Document.class);

                if (lock != null && lock.getString("server") != null) {
                    return lock.getString("server");
                }
            }
        } catch (RuntimeException exception) {
            logger.log(Level.FINE, "Could not read who holds the lock for " + uuid, exception);
        }

        return "Unknown";
    }

    public boolean save(PlayerSnapshot data, boolean releaseLock) {
        Document encoded = PlayerDataCodec.encode(data);
        encoded.remove(FIELD_ID);

        Document update = new Document();

        if (releaseLock) {
            update.append("$set", encoded)
                    .append("$unset", new Document(FIELD_LOCK, ""));
        } else {
            encoded.append(FIELD_LOCK_RENEWED_AT, System.currentTimeMillis());
            update.append("$set", encoded);
        }

        Bson filter = Filters.and(
                Filters.eq(FIELD_ID, data.uuid()),
                Filters.eq(FIELD_LOCK_SERVER, settings.getServerId()));

        long matched = collection.updateOne(filter, update).getMatchedCount();
        return matched > 0L;
    }

    public int saveAll(Collection<PlayerSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return 0;
        }

        List<WriteModel<Document>> writes = new ArrayList<>(snapshots.size());

        for (PlayerSnapshot snapshot : snapshots) {
            Document encoded = PlayerDataCodec.encode(snapshot);
            encoded.remove(FIELD_ID);

            writes.add(new UpdateOneModel<>(
                    // Same condition as the single save: it only writes if this server still holds the lock.
                    Filters.and(
                            Filters.eq(FIELD_ID, snapshot.uuid()),
                            Filters.eq(FIELD_LOCK_SERVER, settings.getServerId())),
                    new Document("$set", encoded)
                            .append("$unset", new Document(FIELD_LOCK, ""))));
        }

        BulkWriteResult result = collection.bulkWrite(writes, new BulkWriteOptions().ordered(false));
        return result.getMatchedCount();
    }

    public void renew(UUID uuid) {
        collection.updateOne(
                Filters.and(
                        Filters.eq(FIELD_ID, uuid),
                        Filters.eq(FIELD_LOCK_SERVER, settings.getServerId())),
                Updates.set(FIELD_LOCK_RENEWED_AT, System.currentTimeMillis()));
    }

    public void renewAll(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return;
        }

        collection.updateMany(
                Filters.and(
                        Filters.in(FIELD_ID, uuids),
                        Filters.eq(FIELD_LOCK_SERVER, settings.getServerId())),
                Updates.set(FIELD_LOCK_RENEWED_AT, System.currentTimeMillis()));
    }

    public void release(UUID uuid) {
        collection.updateOne(
                Filters.and(
                        Filters.eq(FIELD_ID, uuid),
                        Filters.eq(FIELD_LOCK_SERVER, settings.getServerId())),
                Updates.unset(FIELD_LOCK));
    }

    public PlayerSnapshot find(UUID uuid) {
        return PlayerDataCodec.decode(collection.find(Filters.eq(FIELD_ID, uuid)).first());
    }

    public record LockInfo(String server, long acquiredAt, long renewedAt) {
        public boolean isStale(long leaseDurationMillis) {
            return System.currentTimeMillis() - renewedAt > leaseDurationMillis;
        }
    }

    public LockInfo findLock(UUID uuid) {
        Document document = collection.find(Filters.eq(FIELD_ID, uuid))
                .projection(new Document(FIELD_LOCK, 1))
                .first();

        if (document == null) {
            return null;
        }

        Document lock = document.get(FIELD_LOCK, Document.class);

        if (lock == null || lock.getString("server") == null) {
            return null;
        }

        return new LockInfo(
                lock.getString("server"),
                lock.get("acquiredAt") instanceof Number acquiredAt ? acquiredAt.longValue() : 0L,
                lock.get("renewedAt") instanceof Number renewedAt ? renewedAt.longValue() : 0L);
    }

    public PlayerSnapshot findByName(String name) {
        Document document = collection.find(Filters.eq("profile.name", name)).first();

        if (document == null) {
            document = collection.find(
                    Filters.regex("profile.name", "^" + java.util.regex.Pattern.quote(name) + "$", "i")).first();
        }

        return PlayerDataCodec.decode(document);
    }

    public boolean forceRelease(UUID uuid) {
        return collection.updateOne(
                Filters.eq(FIELD_ID, uuid),
                Updates.unset(FIELD_LOCK)).getModifiedCount() > 0L;
    }
}
