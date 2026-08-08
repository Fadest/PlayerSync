package net.keyber.sync.data;

import lombok.experimental.UtilityClass;
import net.keyber.sync.data.impl.*;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.*;

import static net.keyber.sync.util.ParseUtils.*;

@UtilityClass
public final class PlayerDataCodec {
    public final int SCHEMA_VERSION = 1;

    public final String FIELD_ID = "_id";
    public final String FIELD_SCHEMA = "schemaVersion";
    public final String FIELD_PROFILE = "profile";
    public final String FIELD_STATE = "state";
    public final String FIELD_LOCATIONS = "locations";
    public final String FIELD_INVENTORY = "inventory";
    public final String FIELD_STATISTICS = "statistics";
    public final String FIELD_ADVANCEMENTS = "advancements";

    public Document encode(PlayerSnapshot data) {
        Document document = new Document(FIELD_ID, data.uuid()).append(FIELD_SCHEMA, SCHEMA_VERSION);

        if (data.profile() != null) {
            document.append(FIELD_PROFILE, encodeProfile(data.profile()));
        }

        if (data.state() != null) {
            document.append(FIELD_STATE, encodeState(data.state()));
        }

        if (data.locations() != null) {
            document.append(FIELD_LOCATIONS, encodeLocations(data.locations()));
        }

        if (data.inventory() != null) {
            document.append(FIELD_INVENTORY, encodeInventory(data.inventory()));
        }

        if (data.statistics() != null) {
            document.append(FIELD_STATISTICS, new Document(new LinkedHashMap<>(data.statistics())));
        }

        if (data.advancements() != null) {
            document.append(FIELD_ADVANCEMENTS, encodeAdvancements(data.advancements()));
        }

        return document;
    }

    private List<Document> encodeAdvancements(AdvancementsData advancements) {
        List<Document> encoded = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : advancements.awarded().entrySet()) {
            encoded.add(new Document("key", entry.getKey()).append("criteria", new ArrayList<>(entry.getValue())));
        }

        return encoded;
    }

    private Document encodeProfile(ProfileData profile) {
        return new Document("name", profile.name())
                .append("firstSeen", profile.firstSeen())
                .append("lastSeen", profile.lastSeen())
                .append("lastServer", profile.lastServer());
    }

    private Document encodeState(StateData state) {
        Document document = new Document("health", state.health())
                .append("maxHealth", state.maxHealth())
                .append("foodLevel", state.foodLevel())
                .append("saturation", (double) state.saturation())
                .append("exhaustion", (double) state.exhaustion())
                .append("level", state.level())
                .append("experience", (double) state.experience())
                .append("totalExperience", state.totalExperience())
                .append("gameMode", state.gameMode())
                .append("allowFlight", state.allowFlight())
                .append("flying", state.flying())
                .append("walkSpeed", (double) state.walkSpeed())
                .append("flySpeed", (double) state.flySpeed())
                .append("fireTicks", state.fireTicks())
                .append("fallDistance", (double) state.fallDistance());

        if (state.effects() != null) {
            List<Document> effects = new ArrayList<>();

            for (EffectData effect : state.effects()) {
                effects.add(encodeEffect(effect));
            }

            document.append("effects", effects);
        }

        return document;
    }

    private Document encodeEffect(EffectData effect) {
        return new Document("type", effect.type())
                .append("duration", effect.duration())
                .append("amplifier", effect.amplifier())
                .append("ambient", effect.ambient())
                .append("particles", effect.particles())
                .append("icon", effect.icon());
    }

    private Document encodeLocations(LocationsData locations) {
        Document document = new Document();

        putIfPresent(document, "last", encodeLocation(locations.last()));
        putIfPresent(document, "respawn", encodeLocation(locations.respawn()));

        if (locations.perWorld() != null && !locations.perWorld().isEmpty()) {
            List<Document> perWorld = new ArrayList<>();

            for (Map.Entry<UUID, LocationData> entry : locations.perWorld().entrySet()) {
                perWorld.add(encodeLocation(entry.getValue()));
            }

            document.append("perWorld", perWorld);
        }

        return document;
    }

    private Document encodeLocation(LocationData location) {
        if (location == null) {
            return null;
        }

        return new Document("world", location.uid())
                .append("x", location.x())
                .append("y", location.y())
                .append("z", location.z())
                .append("yaw", (double) location.yaw())
                .append("pitch", (double) location.pitch());
    }

    private Document encodeInventory(InventoryData inventory) {
        Document document = new Document("heldSlot", inventory.heldSlot());

        putIfPresent(document, "main", encodeContainer(inventory.main()));
        putIfPresent(document, "armor", encodeContainer(inventory.armor()));
        putIfPresent(document, "offHand", encodeContainer(inventory.offHand()));
        putIfPresent(document, "enderChest", encodeContainer(inventory.enderChest()));

        return document;
    }

    private Document encodeContainer(ItemContainer container) {
        if (container == null) {
            return null;
        }

        Document slots = new Document();

        for (Map.Entry<Integer, byte[]> entry : container.slots().entrySet()) {
            slots.append(String.valueOf(entry.getKey()), new Binary(entry.getValue()));
        }

        return new Document("size", container.size()).append("slots", slots);
    }

    private void putIfPresent(Document document, String key, Object value) {
        if (value != null) {
            document.append(key, value);
        }
    }

    public PlayerSnapshot decode(Document document) {
        if (document == null) {
            return null;
        }

        UUID uuid = document.get(FIELD_ID, UUID.class);
        int version = intOf(document, FIELD_SCHEMA, SCHEMA_VERSION);

        if (version > SCHEMA_VERSION) {
            throw new IllegalStateException(
                    String.format("Schema Version %d is not supported for player %s", version, uuid.toString()));
        }

        return new PlayerSnapshot(
                uuid,
                decodeProfile(document.get(FIELD_PROFILE, Document.class)),
                decodeState(document.get(FIELD_STATE, Document.class)),
                decodeLocations(document.get(FIELD_LOCATIONS, Document.class)),
                decodeInventories(document.get(FIELD_INVENTORY, Document.class)),
                decodeStatistics(document.get(FIELD_STATISTICS, Document.class)),
                decodeAdvancements(document.getList(FIELD_ADVANCEMENTS, Document.class)));
    }

    private ProfileData decodeProfile(Document document) {
        if (document == null) {
            return null;
        }

        return new ProfileData(
                document.getString("name"),
                longOf(document, "firstSeen", 0L),
                longOf(document, "lastSeen", 0L),
                document.getString("lastServer"));
    }

    private StateData decodeState(Document document) {
        if (document == null) {
            return null;
        }

        List<EffectData> effects = new ArrayList<>();
        List<?> rawEffects = document.getList("effects", Document.class);

        if (rawEffects != null) {
            for (Object raw : rawEffects) {
                EffectData effect = decodeEffect((Document) raw);

                if (effect != null) {
                    effects.add(effect);
                }
            }
        }

        return new StateData(
                doubleOf(document, "health", 20.0D),
                doubleOf(document, "maxHealth", 20.0D),
                intOf(document, "foodLevel", 20),
                floatOf(document, "saturation", 5.0F),
                floatOf(document, "exhaustion", 0.0F),
                intOf(document, "level", 0),
                floatOf(document, "experience", 0.0F),
                intOf(document, "totalExperience", 0),
                document.getString("gameMode"),
                document.getBoolean("allowFlight", false),
                document.getBoolean("flying", false),
                floatOf(document, "walkSpeed", 0.2F),
                floatOf(document, "flySpeed", 0.1F),
                intOf(document, "fireTicks", 0),
                floatOf(document, "fallDistance", 0.0F),
                effects);
    }

    private EffectData decodeEffect(Document document) {
        if (document == null || document.getString("type") == null) {
            return null;
        }

        return new EffectData(
                document.getString("type"),
                intOf(document, "duration", 0),
                intOf(document, "amplifier", 0),
                document.getBoolean("ambient", false),
                document.getBoolean("particles", true),
                document.getBoolean("icon", true));
    }

    private LocationsData decodeLocations(Document document) {
        if (document == null) {
            return null;
        }

        Map<UUID, LocationData> perWorld = new LinkedHashMap<>();
        List<?> rawPerWorld = document.getList("perWorld", Document.class);

        if (rawPerWorld != null) {
            for (Object raw : rawPerWorld) {
                LocationData location = decodeLocation((Document) raw);

                if (location != null) {
                    perWorld.put(location.uid(), location);
                }
            }
        }

        return new LocationsData(
                decodeLocation(document.get("last", Document.class)),
                decodeLocation(document.get("respawn", Document.class)),
                perWorld);
    }

    private LocationData decodeLocation(Document document) {
        if (document == null) {
            return null;
        }

        UUID world = document.get("world", UUID.class);
        if (world == null) {
            return null;
        }

        return new LocationData(
                world,
                doubleOf(document, "x", 0.0D),
                doubleOf(document, "y", 0.0D),
                doubleOf(document, "z", 0.0D),
                floatOf(document, "yaw", 0.0F),
                floatOf(document, "pitch", 0.0F));
    }

    private InventoryData decodeInventories(Document document) {
        if (document == null) {
            return null;
        }

        return new InventoryData(
                decodeContainer(document.get("main", Document.class)),
                decodeContainer(document.get("armor", Document.class)),
                decodeContainer(document.get("offHand", Document.class)),
                intOf(document, "heldSlot", 0),
                decodeContainer(document.get("enderChest", Document.class)));
    }

    private ItemContainer decodeContainer(Document document) {
        if (document == null) {
            return null;
        }

        Document slots = document.get("slots", Document.class);
        Map<Integer, byte[]> items = new LinkedHashMap<>();

        if (slots != null) {
            for (Map.Entry<String, Object> entry : slots.entrySet()) {
                byte[] bytes = binaryOf(entry.getValue());

                if (bytes == null) {
                    continue;
                }

                try {
                    items.put(Integer.parseInt(entry.getKey()), bytes);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return new ItemContainer(intOf(document, "size", 0), items);
    }

    private Map<String, Integer> decodeStatistics(Document document) {
        if (document == null) {
            return null;
        }

        Map<String, Integer> statistics = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                statistics.put(entry.getKey(), number.intValue());
            }
        }

        return statistics;
    }

    private AdvancementsData decodeAdvancements(List<Document> documents) {
        if (documents == null) {
            return null;
        }

        Map<String, Set<String>> awarded = new LinkedHashMap<>();
        for (Document document : documents) {
            String key = document.getString("key");
            List<String> criteria = document.getList("criteria", String.class);

            if (key == null || criteria == null || criteria.isEmpty()) {
                continue;
            }

            awarded.put(key, Set.copyOf(criteria));
        }

        return new AdvancementsData(awarded);
    }
}
