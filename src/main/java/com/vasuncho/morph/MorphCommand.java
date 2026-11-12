package com.vasuncho.morph;

import com.vasuncho.morph.ProfileFetcher.LookupResult;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jspecify.annotations.Nullable;

/** /morph &lt;username&gt; */
public final class MorphCommand implements TabExecutor {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final MorphPlugin plugin;
    private final MorphManager manager;
    private final ProfileFetcher fetcher;

    public MorphCommand(MorphPlugin plugin, MorphManager manager, ProfileFetcher fetcher) {
        this.plugin = plugin;
        this.manager = manager;
        this.fetcher = fetcher;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MorphManager.error("Only players can use this command."));
            return true;
        }
        if (!manager.isAuthorized(player)) {
            player.sendMessage(MorphManager.NOT_OP);
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(MorphManager.error("Usage: /morph <username>"));
            return true;
        }
        String name = args[0];
        if (!USERNAME.matcher(name).matches()) {
            player.sendMessage(MorphManager.error("Invalid username. Use 3-16 letters, digits or underscores."));
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (manager.isPending(uuid)) {
            player.sendMessage(MorphManager.ALREADY_PENDING);
            return true;
        }
        if (manager.isMorphed(uuid)) {
            player.sendMessage(MorphManager.error("You are already morphed. Use /unmorph first."));
            return true;
        }
        if (manager.realName(player).equalsIgnoreCase(name)) {
            player.sendMessage(MorphManager.error("You cannot morph into yourself."));
            return true;
        }
        if (manager.isOnline(null, name)) {
            player.sendMessage(MorphManager.error(name + " is online right now."));
            return true;
        }
        Object token = manager.markPending(uuid);
        if (token == null) {
            player.sendMessage(MorphManager.ALREADY_PENDING);
            return true;
        }

        player.sendMessage(Component.text("Looking up " + name + "...", NamedTextColor.GRAY));
        fetcher.lookup(name).thenAccept(result -> runSync(() -> handleResult(uuid, token, result)));
        return true;
    }

    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin disabled between the check and the schedule; shutdown already cleared all state.
        }
    }

    private void handleResult(UUID uuid, Object token, LookupResult result) {
        if (!manager.isCurrentAttempt(uuid, token)) {
            return; // the player quit (clearing the flag) or a newer attempt replaced this one
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            manager.clearPending(uuid);
            return;
        }
        Component error = switch (result) {
            case LookupResult.Found found -> preflight(player, found);
            case LookupResult.NotFound ignored -> MorphManager.error("No Minecraft account has that username.");
            case LookupResult.RateLimited ignored ->
                    MorphManager.error("Mojang is rate limiting lookups. Try again in a minute.");
            case LookupResult.TimedOut ignored -> MorphManager.error("The Mojang lookup timed out. Try again.");
            case LookupResult.Failed failed -> MorphManager.error("Could not look up that player: " + failed.reason());
        };
        if (error != null) {
            manager.clearPending(uuid);
            player.sendMessage(error);
            return;
        }
        manager.startMorphAnimation(player, (LookupResult.Found) result);
    }

    private @Nullable Component preflight(Player player, LookupResult.Found found) {
        if (!manager.isAuthorized(player)) {
            return MorphManager.NOT_OP;
        }
        return manager.checkTargetConflicts(player, found.uuid(), found.name());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
