package com.vasuncho.morph;

import com.vasuncho.morph.ProfileFetcher.LookupResult;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jspecify.annotations.Nullable;

/**
 * The two-second particle/sound sequence played before a morph or unmorph. Runs every tick; the identity swap
 * happens on the tick through the {@code onClimax} callback.
 * Ts lwk fried :sob:
 */
public final class MorphAnimation extends BukkitRunnable {

    private static final int DURATION_TICKS = 40;
    private static final int CHIME_INTERVAL_TICKS = 10;
    private static final double START_RADIUS = 0.8;
    private static final double END_RADIUS = 0.3;
    private static final double HELIX_HEIGHT = 1.9;
    private static final double RADIANS_PER_TICK = Math.PI / 8;
    private static final int POINTS_PER_TICK = 3;

    private static final Particle HELIX_PARTICLE_A = Particle.WITCH;
    private static final Particle HELIX_PARTICLE_B = Particle.PORTAL;
    private static final Particle BURST_CLOUD = Particle.CLOUD;
    private static final Particle BURST_PORTAL = Particle.REVERSE_PORTAL;
    private static final Particle BURST_FLASH = Particle.FLASH;
    private static final Color FLASH_COLOR = Color.WHITE;

    private static final Sound START_SOUND = Sound.BLOCK_BEACON_POWER_SELECT;
    private static final Sound CHIME_SOUND = Sound.BLOCK_NOTE_BLOCK_CHIME;
    private static final float CHIME_START_PITCH = 0.5f;
    private static final float CHIME_END_PITCH = 2.0f;
    private static final Sound CLIMAX_SOUND_A = Sound.ENTITY_ILLUSIONER_MIRROR_MOVE;
    private static final Sound CLIMAX_SOUND_B = Sound.ENTITY_ENDERMAN_TELEPORT;

    private final MorphPlugin plugin;
    private final MorphManager manager;
    private final Player player;
    private final World startWorld;
    private final boolean morphing;
    private final @Nullable UUID targetUuid;
    private final String targetName;
    private final Component actionBar;
    private final Runnable onClimax;
    private int tick;
    private boolean ended;

    private MorphAnimation(MorphPlugin plugin, MorphManager manager, Player player, boolean morphing,
                           @Nullable UUID targetUuid, String targetName, Component actionBar, Runnable onClimax) {
        this.plugin = plugin;
        this.manager = manager;
        this.player = player;
        this.startWorld = player.getWorld();
        this.morphing = morphing;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.actionBar = actionBar;
        this.onClimax = onClimax;
    }

    static MorphAnimation morph(MorphPlugin plugin, MorphManager manager, Player player,
                                LookupResult.Found target, Runnable onClimax) {
        return new MorphAnimation(plugin, manager, player, true, target.uuid(), target.name(),
                Component.text("Morphing into " + target.name() + "...", NamedTextColor.LIGHT_PURPLE), onClimax);
    }

    static MorphAnimation unmorph(MorphPlugin plugin, MorphManager manager, Player player,
                                  MorphData data, Runnable onClimax) {
        return new MorphAnimation(plugin, manager, player, false, null, data.realName(),
                Component.text("Returning to " + data.realName() + "...", NamedTextColor.LIGHT_PURPLE), onClimax);
    }

    public Player player() {
        return player;
    }

    /** True if this is a morph (not unmorph) animation heading for the given identity. */
    public boolean isMorphInto(UUID uuid, String name) {
        return morphing && (uuid.equals(targetUuid) || targetName.equalsIgnoreCase(name));
    }

    @Override
    public void run() {
        if (ended) {
            return;
        }
        String failure = failureReason();
        if (failure != null) {
            abort(failure);
            return;
        }

        Location base = player.getLocation();
        if (tick >= DURATION_TICKS) {
            if (morphing && !manager.isAuthorized(player)) {
                end();
                player.sendMessage(MorphManager.NOT_OP);
                return;
            }
            if (morphing) {
                Component conflict = manager.checkTargetConflicts(player, targetUuid, targetName);
                if (conflict != null) {
                    end();
                    player.sendMessage(conflict);
                    return;
                }
            } else if (!manager.isMorphed(player.getUniqueId())) {
                end();
                return;
            }
            playClimax(base);
            end();
            onClimax.run();
            player.sendActionBar(Component.empty());
            return;
        }

        player.sendActionBar(actionBar);
        drawHelixes(base);
        if (tick == 0) {
            startWorld.playSound(base, START_SOUND, 1.0f, 1.0f);
        }
        if (tick % CHIME_INTERVAL_TICKS == 0) {
            float progress = tick / (float) (DURATION_TICKS - CHIME_INTERVAL_TICKS);
            float pitch = CHIME_START_PITCH + (CHIME_END_PITCH - CHIME_START_PITCH) * progress;
            startWorld.playSound(base, CHIME_SOUND, 1.0f, pitch);
        }
        tick++;
    }

    private @Nullable String failureReason() {
        if (!plugin.isEnabled()) {
            return "the plugin was disabled";
        }
        if (!player.isOnline()) {
            return "you left the game";
        }
        if (player.isDead()) {
            return "you died";
        }
        if (!player.getWorld().equals(startWorld)) {
            return "you changed world";
        }
        if (morphing && manager.isOnline(targetUuid, targetName)) {
            return targetName + " came online";
        }
        return null;
    }

    private void drawHelixes(Location base) {
        double step = 1.0 / POINTS_PER_TICK;
        for (int i = 0; i < POINTS_PER_TICK; i++) {
            double t = tick + i * step;
            double progress = t / DURATION_TICKS;
            double radius = START_RADIUS + (END_RADIUS - START_RADIUS) * progress;
            double y = HELIX_HEIGHT * progress;
            double angle = t * RADIANS_PER_TICK;
            spawn(HELIX_PARTICLE_A, base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
            spawn(HELIX_PARTICLE_B, base.clone().add(Math.cos(-angle + Math.PI) * radius, y, Math.sin(-angle + Math.PI) * radius));
        }
    }

    private void spawn(Particle particle, Location location) {
        startWorld.spawnParticle(particle, location, 1, 0, 0, 0, 0);
    }

    private void playClimax(Location base) {
        Location centre = base.clone().add(0, HELIX_HEIGHT / 2, 0);
        startWorld.spawnParticle(BURST_CLOUD, centre, 40, 0.4, 0.6, 0.4, 0.08);
        startWorld.spawnParticle(BURST_PORTAL, centre, 80, 0.4, 0.6, 0.4, 0.4);
        startWorld.spawnParticle(BURST_FLASH, centre, 1, 0, 0, 0, 0, FLASH_COLOR);
        startWorld.playSound(centre, CLIMAX_SOUND_A, 1.0f, 1.0f);
        startWorld.playSound(centre, CLIMAX_SOUND_B, 1.0f, 1.0f);
    }

    /** Stops the animation without applying anything and tells the player why, if they are still online. */
    public void abort(String reason) {
        if (ended) {
            return;
        }
        end();
        if (player.isOnline()) {
            player.sendActionBar(Component.empty());
            player.sendMessage(MorphManager.error((morphing ? "Morph" : "Unmorph") + " cancelled: " + reason + "."));
        }
    }

    /** Stops the animation silently (used when the player is disconnecting). */
    public void stopSilently() {
        if (!ended) {
            end();
        }
    }

    private void end() {
        ended = true;
        if (!isCancelled()) {
            cancel();
        }
        manager.animationEnded(player.getUniqueId(), this);
    }
}
