package net.keyber.sync.event;

import lombok.Getter;
import net.keyber.sync.data.PlayerSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@Getter
public class PlayerDataLoadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uniqueId;
    private final String playerName;
    private PlayerSnapshot snapshot;

    public PlayerDataLoadEvent(UUID uniqueId, String playerName, PlayerSnapshot snapshot) {
        super(true);

        this.uniqueId = uniqueId;
        this.playerName = playerName;
        this.snapshot = snapshot;
    }

    public void setSnapshot(PlayerSnapshot snapshot) {
        if (snapshot == null) {
            snapshot = PlayerSnapshot.empty(this.uniqueId);
        }
        this.snapshot = snapshot;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
