package com.vasuncho.morph;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

public final class MorphListener implements Listener {

    private static final ChatRenderer VANILLA_RENDERER = (source, sourceDisplayName, message, viewer) ->
            Component.translatable("chat.type.text", sourceDisplayName, message);

    private final MorphPlugin plugin;
    private final MorphManager manager;

    public MorphListener(MorphPlugin plugin, MorphManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ---- chat ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // displayName() was set to the target's name, so this renders "<Target> message" exactly like vanilla.
        if (manager.isMorphed(event.getPlayer().getUniqueId())) {
            event.renderer(VANILLA_RENDERER);
        }
    }

    // ---- the real target logging in ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !plugin.isEnabled()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        // This thread is a login thread, so blocking it until the main thread has reverted the impostor is safe
        // and guarantees the revert happens before the target's PlayerJoinEvent.
        try {
            Future<Void> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                evictImpostors(uuid, name);
                return null;
            });
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not revert morphs for joining player " + name, e);
        }
    }

    private void evictImpostors(UUID uuid, String name) {
        for (MorphAnimation animation : manager.allAnimations()) {
            if (animation.isMorphInto(uuid, name)) {
                animation.abort(name + " came online");
            }
        }
        for (MorphData data : new ArrayList<>(manager.allMorphs())) {
            if (!data.targetUuid().equals(uuid) && !data.targetName().equalsIgnoreCase(name)) {
                continue;
            }
            Player impostor = Bukkit.getPlayer(data.realUuid());
            if (impostor == null) {
                continue;
            }
            MorphAnimation animation = manager.animation(impostor.getUniqueId());
            if (animation != null) {
                animation.stopSilently();
            }
            manager.revert(impostor, true, data.targetName() + " logged in");
            impostor.sendMessage(MorphManager.error("You were unmorphed because the real "
                    + data.targetName() + " just joined the server."));
        }
    }

    // ---- join / quit ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
        manager.grantAllowlistPermission(event.getPlayer());
    }

    /** Also covers kicks and bans: a kicked player always ends in a PlayerQuitEvent. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        MorphAnimation animation = manager.animation(uuid);
        if (animation != null) {
            animation.stopSilently();
        }
        manager.clearPending(uuid);
        manager.forgetAllowlistPermission(uuid);

        MorphData data = manager.morphData(uuid);
        if (data == null) {
            return;
        }
        if (event.quitMessage() != null) {
            event.quitMessage(MorphManager.leaveMessage(data.targetName(), data.targetUuid()));
        }
        manager.revertOnQuit(player);
    }

    // ---- death messages and advancements -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message != null) {
            event.deathMessage(hideRealNames(message));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        if (message != null) {
            event.message(hideRealNames(message));
        }
    }

    /** Safety net: swaps any morphed player's real name for their target name (victim, killer, advancer). */
    private Component hideRealNames(Component message) {
        Component result = message;
        for (MorphData data : manager.allMorphs()) {
            Pattern realName = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(data.realName()) + "(?![A-Za-z0-9_])");
            result = result.replaceText(TextReplacementConfig.builder()
                    .match(realName)
                    .replacement(data.targetName())
                    .build());
        }
        return result;
    }

    // ---- server list ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        List<PaperServerListPingEvent.ListedPlayerInfo> listed = event.getListedPlayers();
        for (ListIterator<PaperServerListPingEvent.ListedPlayerInfo> it = listed.listIterator(); it.hasNext(); ) {
            MorphData data = find(it.next());
            if (data != null) {
                it.set(new PaperServerListPingEvent.ListedPlayerInfo(data.targetName(), data.targetUuid()));
            }
        }
    }

    private @Nullable MorphData find(PaperServerListPingEvent.ListedPlayerInfo info) {
        MorphData byUuid = manager.morphData(info.id());
        if (byUuid != null) {
            return byUuid;
        }
        for (MorphData data : manager.allMorphs()) {
            if (data.realName().equalsIgnoreCase(info.name())) {
                return data;
            }
        }
        return null;
    }
}
