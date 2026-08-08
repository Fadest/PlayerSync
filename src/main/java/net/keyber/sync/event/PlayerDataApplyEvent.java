package net.keyber.sync.event;

import lombok.Getter;
import net.keyber.sync.data.PlayerSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

@Getter
public class PlayerDataApplyEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private PlayerSnapshot snapshot;

    public PlayerDataApplyEvent(Player player, PlayerSnapshot snapshot) {
        super(player);

        this.snapshot = snapshot;
    }

    public void setSnapshot(PlayerSnapshot snapshot) {
        if (snapshot == null) {
            snapshot = PlayerSnapshot.empty(this.player.getUniqueId());
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
