package net.keyber.sync.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import lombok.RequiredArgsConstructor;
import net.keyber.sync.data.PlayerSnapshot;
import net.keyber.sync.service.SyncService;
import net.keyber.sync.service.SyncSettings;
import net.keyber.sync.storage.PlayerRepository;
import net.keyber.sync.storage.mongo.MongoManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class PlayerSyncCommand {
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS = (context, builder) -> {
        String remaining = builder.getRemainingLowerCase();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }

        return builder.buildFuture();
    };
    private static final Component PREFIX = Component.text("[PlayerSync] ", NamedTextColor.GREEN);

    private final SyncService syncService;
    private final PlayerRepository repository;
    private final SyncSettings settings;
    private final Logger logger;

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("playersync")
                .requires(source -> source.getSender().hasPermission("playersync.command"))
                .executes(context -> {
                    usage(context.getSource().getSender());
                    return 1;
                })
                .then(infoArgument())
                .then(saveArgument())
                .then(unlockArgument())
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> infoArgument() {
        return Commands.literal("info")
                .requires(source -> source.getSender().hasPermission("playersync.command.info"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(context -> {
                            info(context.getSource().getSender(), StringArgumentType.getString(context, "player"));
                            return 1;
                        }));
    }

    private void info(CommandSender sender, String target) {
        runAsync(sender, () -> {
            PlayerSnapshot snapshot = resolve(target);

            if (snapshot == null) {
                error(sender, "No stored data for '" + target + "'.");
                return;
            }

            PlayerRepository.LockInfo lock = repository.findLock(snapshot.uuid());

            send(
                    sender,
                    Component.text("Data for ", NamedTextColor.GRAY)
                            .append(Component.text(nameOf(snapshot), NamedTextColor.WHITE)));
            detail(sender, "UUID", snapshot.uuid().toString());

            if (snapshot.profile() != null) {
                detail(sender, "First login", timestamp(snapshot.profile().firstSeen()));
                detail(sender, "Last save", timestamp(snapshot.profile().lastSeen()));
                detail(sender, "Last server", String.valueOf(snapshot.profile().lastServer()));
            }

            detail(sender, "Inventory", describeInventory(snapshot));
            detail(
                    sender,
                    "Statistics",
                    snapshot.statistics() == null
                            ? "not synced"
                            : snapshot.statistics().size() + " stored");
            detail(
                    sender,
                    "Advancements",
                    snapshot.advancements() == null
                            ? "not synced"
                            : snapshot.advancements().awarded().size() + " with progress");

            if (lock == null) {
                detail(sender, "Lock", "free");
            } else {
                detail(
                        sender,
                        "Lock",
                        lock.server()
                                + " (renewed " + elapsed(lock.renewedAt())
                                + (lock.isStale(settings.getLeaseDurationMillis()) ? ", expired" : " ago") + ")");
            }
        });
    }

    private String describeInventory(PlayerSnapshot snapshot) {
        if (snapshot.inventory() == null || snapshot.inventory().main() == null) {
            return "not synced";
        }

        return snapshot.inventory().main().slots().size() + " slots used";
    }

    private LiteralArgumentBuilder<CommandSourceStack> saveArgument() {
        return Commands.literal("save")
                .requires(source -> source.getSender().hasPermission("playersync.command.save"))
                .then(Commands.literal("all").executes(context -> {
                    CommandSender sender = context.getSource().getSender();

                    // Spread across ticks: capturing everyone in a single tick is
                    // exactly the spike the auto-save avoids.
                    syncService.saveAllOnline(saved -> success(sender, "Saved " + saved + " player(s)."));
                    return 1;
                }))
                .then(Commands.argument("player", ArgumentTypes.player()).executes(context -> {
                    savePlayers(context, context.getSource().getSender());
                    return 1;
                }));
    }

    private void savePlayers(CommandContext<CommandSourceStack> context, CommandSender sender) {
        PlayerSelectorArgumentResolver resolver = context.getArgument("player", PlayerSelectorArgumentResolver.class);

        List<Player> players;
        try {
            players = resolver.resolve(context.getSource());
        } catch (CommandSyntaxException exception) {
            error(sender, exception.getMessage());
            return;
        }

        for (Player player : players) {
            syncService.saveNow(player).thenAccept(written -> {
                if (written) {
                    success(sender, "Saved " + player.getName() + ".");
                } else {
                    error(sender, "This server no longer holds '" + player.getName() + "' session lock.");
                }
            });
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> unlockArgument() {
        return Commands.literal("unlock")
                .requires(source -> source.getSender().hasPermission("playersync.command.unlock"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(context -> {
                            unlock(
                                    context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"),
                                    false);
                            return 1;
                        })
                        .then(Commands.literal("force").executes(context -> {
                            unlock(
                                    context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"),
                                    true);

                            return 1;
                        })));
    }

    private void unlock(CommandSender sender, String target, boolean force) {
        runAsync(sender, () -> {
            PlayerSnapshot snapshot = resolve(target);

            if (snapshot == null) {
                error(sender, "No stored data for '" + target + "'.");
                return;
            }

            PlayerRepository.LockInfo lock = repository.findLock(snapshot.uuid());

            if (lock == null) {
                send(sender, Component.text("No lock is held for this player.", NamedTextColor.GRAY));
                return;
            }

            // A lease that is still being renewed means the server holding it is alive and
            // believes the player is on it. Releasing it there would let that player's data
            // be written from two places at once, which is what the lease exists to prevent.
            if (!lock.isStale(settings.getLeaseDurationMillis()) && !force) {
                error(
                        sender,
                        "The lock is held by '" + lock.server() + "' and is still active " + "(renewed "
                                + elapsed(lock.renewedAt()) + " ago).");
                return;
            }

            if (repository.forceRelease(snapshot.uuid())) {
                success(sender, "Lock for " + nameOf(snapshot) + " released (it was held by '" + lock.server() + "').");
            } else {
                error(sender, "Could not release the lock.");
            }
        });
    }

    private PlayerSnapshot resolve(String input) {
        try {
            return repository.find(UUID.fromString(input));
        } catch (IllegalArgumentException notAUuid) {
            return repository.findByName(input);
        }
    }

    private String nameOf(PlayerSnapshot snapshot) {
        if (snapshot.profile() != null && snapshot.profile().name() != null) {
            return snapshot.profile().name();
        }

        return snapshot.uuid().toString();
    }

    private void runAsync(CommandSender sender, Runnable work) {
        MongoManager.getInstance().runAsync(() -> {
            try {
                work.run();
            } catch (RuntimeException exception) {
                error(sender, "An error occurred, try again later.");
                logger.log(Level.WARNING, "A PlayerSync command failed", exception);
            }
        });
    }

    private String timestamp(long epochMillis) {
        return epochMillis <= 0L ? "unknown" : elapsed(epochMillis) + " ago";
    }

    private String elapsed(long epochMillis) {
        Duration duration = Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - epochMillis));

        if (duration.toDays() > 0L) {
            return duration.toDays() + "d";
        }

        if (duration.toHours() > 0L) {
            return duration.toHours() + "h";
        }

        if (duration.toMinutes() > 0L) {
            return duration.toMinutes() + "min";
        }

        return duration.toSeconds() + "s";
    }

    private void usage(CommandSender sender) {
        send(sender, Component.text("Available commands", NamedTextColor.GRAY));
        detail(sender, "/playersync info <player>", "what is stored and who holds the lock");
        detail(sender, "/playersync save <player|all>", "force a save");
        detail(sender, "/playersync unlock <player> [force]", "release a stuck lock");
    }

    private void detail(CommandSender sender, String key, String value) {
        sender.sendMessage(Component.text("  " + key + " ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private void send(CommandSender sender, Component message) {
        sender.sendMessage(PREFIX.append(message));
    }

    private void success(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GREEN));
    }

    private void error(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.RED));
    }
}
