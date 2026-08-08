package net.keyber.sync.event;

import lombok.Getter;
import lombok.Setter;
import net.keyber.sync.data.PlayerSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
public class PlayerDataSaveEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Cause cause;

    @NotNull
    private PlayerSnapshot snapshot;

    private boolean cancelled;

    public PlayerDataSaveEvent(Player player, @NotNull PlayerSnapshot snapshot, Cause cause) {
        super(player);

        this.snapshot = snapshot;
        this.cause = cause;
    }

    public void setSnapshot(@Nullable PlayerSnapshot snapshot) {
        if (snapshot == null) {
            snapshot = PlayerSnapshot.empty(player.getUniqueId());
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

    public enum Cause {
        DISCONNECT,
        AUTO_SAVE,
        TRANSFER,
        SHUTDOWN
    }
}
