package com.vasuncho.morph;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.vasuncho.morph.ProfileFetcher.LookupResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

/**
 * Owns all morph state. Every method except the cache accessors must be called on the main thread.
 */
public final class MorphManager {

    public static final String PERMISSION = "morph.use";
    public static final Component NOT_OP = Component.text("You must be an operator to use this command.", NamedTextColor.RED);
    public static final Component ALREADY_PENDING = Component.text("Already morphing, please wait.", NamedTextColor.RED);

    private static final String LEFT_KEY = "multiplayer.player.left";
    private static final String JOINED_KEY = "multiplayer.player.joined";
    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    /** Real usernames (lower case) allowed to use the commands without being op. */
    private static final Set<String> ALLOWED_NON_OPS = Set.of("ismoshyy");

    private record CachedLookup(LookupResult.Found result, long expiresAt) {
    }

    private final MorphPlugin plugin;
    private final Map<UUID, MorphData> morphs = new ConcurrentHashMap<>();
    /** Player -> token of the current lookup or animation. A player in here may not start another one. */
    private final Map<UUID, Object> pending = new ConcurrentHashMap<>();
    private final Map<UUID, MorphAnimation> animations = new ConcurrentHashMap<>();
    private final Map<String, CachedLookup> lookupCache = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> allowlistAttachments = new ConcurrentHashMap<>();
    /** Players hidden from everyone while quitting morphed; shown again when they next join. */
    private final Set<UUID> hiddenOnQuit = ConcurrentHashMap.newKeySet();

    public MorphManager(MorphPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- state queries -------------------------------------------------------------------------

    public @Nullable MorphData morphData(UUID player) {
        return morphs.get(player);
    }

    public boolean isMorphed(UUID player) {
        return morphs.containsKey(player);
    }

    public Collection<MorphData> allMorphs() {
        return morphs.values();
    }

    public String realName(Player player) {
        MorphData data = morphs.get(player.getUniqueId());
        return data != null ? data.realName() : player.getName();
    }

    /** Ops, plus the allowlisted real usernames. Checked by real name, so morphing as an allowlisted name grants nothing. */
    public boolean isAuthorized(Player player) {
        return player.isOp() || ALLOWED_NON_OPS.contains(realName(player).toLowerCase(Locale.ROOT));
    }

    /** Gives allowlisted players morph.use so Bukkit lets them reach (and tab-complete) the commands. */
    public void grantAllowlistPermission(Player player) {
        if (ALLOWED_NON_OPS.contains(realName(player).toLowerCase(Locale.ROOT))
                && !allowlistAttachments.containsKey(player.getUniqueId())) {
            allowlistAttachments.put(player.getUniqueId(), player.addAttachment(plugin, PERMISSION, true));
            player.updateCommands();
        }
    }

    public void forgetAllowlistPermission(UUID player) {
        allowlistAttachments.remove(player);
    }

    /** True if any online player's REAL uuid or REAL name matches. */
    public boolean isOnline(@Nullable UUID uuid, String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(uuid) || realName(online).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks rules 2c-2f against current state. Returns an error message, or null if the morph may proceed.
     */
    public @Nullable Component checkTargetConflicts(Player sender, UUID targetUuid, String targetName) {
        if (sender.getUniqueId().equals(targetUuid) || realName(sender).equalsIgnoreCase(targetName)) {
            return error("You cannot morph into yourself.");
        }
        if (isMorphed(sender.getUniqueId())) {
            return error("You are already morphed. Use /unmorph first.");
        }
        if (isOnline(targetUuid, targetName)) {
            return error(targetName + " is online right now.");
        }
        for (MorphData data : morphs.values()) {
            if (data.targetUuid().equals(targetUuid)) {
                return error("Someone is already morphed as " + targetName + ".");
            }
        }
        for (MorphAnimation animation : animations.values()) {
            if (animation.player() != sender && animation.isMorphInto(targetUuid, targetName)) {
                return error("Someone is already morphing into " + targetName + ".");
            }
        }
        return null;
    }

    // ---- pending / animations ------------------------------------------------------------------

    public boolean isPending(UUID player) {
        return pending.containsKey(player);
    }

    /** Marks the player pending and returns a token identifying this attempt, or null if already pending. */
    public @Nullable Object markPending(UUID player) {
        Object token = new Object();
        return pending.putIfAbsent(player, token) == null ? token : null;
    }

    public boolean isCurrentAttempt(UUID player, Object token) {
        return pending.get(player) == token;
    }

    public void clearPending(UUID player) {
        pending.remove(player);
    }

    public void startMorphAnimation(Player player, LookupResult.Found target) {
        MorphAnimation animation = MorphAnimation.morph(plugin, this, player, target,
                () -> applyMorph(player, target));
        animations.put(player.getUniqueId(), animation);
        animation.runTaskTimer(plugin, 0L, 1L);
    }

    public void startUnmorphAnimation(Player player, MorphData data) {
        MorphAnimation animation = MorphAnimation.unmorph(plugin, this, player, data,
                () -> revert(player, true, null));
        animations.put(player.getUniqueId(), animation);
        animation.runTaskTimer(plugin, 0L, 1L);
    }

    /** Called by an animation when it finishes or is cancelled. */
    void animationEnded(UUID player, MorphAnimation animation) {
        if (animations.remove(player, animation)) {
            pending.remove(player);
        }
    }

    public @Nullable MorphAnimation animation(UUID player) {
        return animations.get(player);
    }

    public Collection<MorphAnimation> allAnimations() {
        return new ArrayList<>(animations.values());
    }

    // ---- applying / reverting ------------------------------------------------------------------

    void applyMorph(Player player, LookupResult.Found target) {
        UUID uuid = player.getUniqueId();
        String realName = player.getName();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Team realTeam = main.getEntryTeam(realName);
        Team targetTeam = main.getEntryTeam(target.name());

        MorphData data = new MorphData(uuid, realName, player.getPlayerProfile(), player.displayName(),
                player.playerListName(), realTeam != null ? realTeam.getName() : null,
                targetTeam != null ? targetTeam.getName() : null, target.uuid(), target.name());
        morphs.put(uuid, data);

        broadcastFake(LEFT_KEY, realName, uuid);

        PlayerProfile profile = Bukkit.createProfileExact(uuid, target.name());
        if (target.textures() != null) {
            profile.setProperty(target.textures());
        }
        player.setPlayerProfile(profile);
        player.displayName(nameComponent(target.name(), target.uuid()));
        player.playerListName(Component.text(target.name()));

        if (realTeam != null) {
            realTeam.removeEntry(realName);
            realTeam.addEntry(target.name());
        }

        // Keeps /unmorph reachable (Bukkit checks plugin.yml permissions before our executor) if de-opped while morphed.
        attachments.put(uuid, player.addAttachment(plugin, PERMISSION, true));

        broadcastFake(JOINED_KEY, target.name(), target.uuid());
        plugin.getLogger().info(realName + " (" + uuid + ") morphed into " + target.name());
    }

    /**
     * Restores the player's own identity.
     *
     * @param broadcast send fake leave(target) / join(real) messages
     * @param reason    appended to the console log line, or null
     * @return false if the player was not morphed
     */
    public boolean revert(Player player, boolean broadcast, @Nullable String reason) {
        MorphData data = morphs.remove(player.getUniqueId());
        if (data == null) {
            return false;
        }
        if (broadcast) {
            broadcastFake(LEFT_KEY, data.targetName(), data.targetUuid());
        }
        restoreIdentity(player, data);
        if (broadcast) {
            broadcastFake(JOINED_KEY, data.realName(), data.realUuid());
        }
        plugin.getLogger().info(data.realName() + " (" + data.realUuid() + ") unmorphed from " + data.targetName()
                + (reason != null ? " (" + reason + ")" : ""));
        return true;
    }

    /**
     * Reverts a player who is disconnecting. They are hidden from every viewer first, so restoring the real
     * profile (needed so playerdata records the real name) never re-sends the real name to anyone's tab list.
     */
    public void revertOnQuit(Player player) {
        MorphData data = morphs.remove(player.getUniqueId());
        if (data == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != player) {
                viewer.hidePlayer(plugin, player);
            }
        }
        hiddenOnQuit.add(player.getUniqueId());
        restoreIdentity(player, data);
        plugin.getLogger().info(data.realName() + " (" + data.realUuid() + ") unmorphed from " + data.targetName()
                + " (disconnected)");
    }

    /** Undoes the hide from {@link #revertOnQuit} when the player comes back. */
    public void handleJoin(Player player) {
        if (hiddenOnQuit.remove(player.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer != player) {
                    viewer.showPlayer(plugin, player);
                }
            }
        }
    }

    private void restoreIdentity(Player player, MorphData data) {
        player.setPlayerProfile(data.originalProfile());
        player.displayName(data.originalDisplayName());
        player.playerListName(data.originalListName());

        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (data.realTeam() != null) {
            Team realTeam = main.getTeam(data.realTeam());
            if (realTeam != null) {
                realTeam.removeEntry(data.targetName());
                realTeam.addEntry(data.realName());
            }
            Team targetTeam = data.targetTeam() != null ? main.getTeam(data.targetTeam()) : null;
            if (targetTeam != null) {
                targetTeam.addEntry(data.targetName());
            }
        }

        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
        }
        player.updateCommands();
    }

    /** Cancels every animation and reverts every morph. Called from onDisable. */
    public void shutdown() {
        for (MorphAnimation animation : allAnimations()) {
            animation.abort("the plugin was disabled");
        }
        animations.clear();
        pending.clear();
        boolean broadcast = !Bukkit.isStopping();
        for (UUID uuid : new ArrayList<>(morphs.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                revert(player, broadcast, "plugin disabled");
            }
        }
        morphs.clear();
        attachments.clear();
        allowlistAttachments.forEach((uuid, attachment) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.removeAttachment(attachment);
            }
        });
        allowlistAttachments.clear();
    }

    // ---- messages ------------------------------------------------------------------------------

    /** A player name decorated like vanilla's: click to /tell, hover shows the entity, shift-click inserts. */
    public static Component nameComponent(String name, UUID uuid) {
        return Component.text(name)
                .clickEvent(ClickEvent.suggestCommand("/tell " + name + " "))
                .hoverEvent(HoverEvent.showEntity(Key.key("player"), uuid, Component.text(name)))
                .insertion(name);
    }

    public static Component leaveMessage(String name, UUID uuid) {
        return Component.translatable(LEFT_KEY, NamedTextColor.YELLOW, nameComponent(name, uuid));
    }

    private void broadcastFake(String key, String name, UUID uuid) {
        Component message = Component.translatable(key, NamedTextColor.YELLOW, nameComponent(name, uuid));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    // ---- lookup cache (thread-safe) ------------------------------------------------------------

    public LookupResult.@Nullable Found cachedLookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        CachedLookup cached = lookupCache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt() < System.currentTimeMillis()) {
            lookupCache.remove(key, cached);
            return null;
        }
        return cached.result();
    }

    public void cacheLookup(String name, LookupResult.Found result) {
        long expiresAt = System.currentTimeMillis() + CACHE_TTL_MILLIS;
        lookupCache.put(name.toLowerCase(Locale.ROOT), new CachedLookup(result, expiresAt));
        lookupCache.put(result.name().toLowerCase(Locale.ROOT), new CachedLookup(result, expiresAt));
    }
}
