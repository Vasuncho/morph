package com.vasuncho.morph;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Everything needed to undo a morph.
 *
 * @param realTeam   main-scoreboard team that held the real name, or null if none
 * @param targetTeam main-scoreboard team that held the target name before the morph, or null if none
 */
public record MorphData(
        UUID realUuid,
        String realName,
        PlayerProfile originalProfile,
        Component originalDisplayName,
        Component originalListName,
        @Nullable String realTeam,
        @Nullable String targetTeam,
        UUID targetUuid,
        String targetName
) {
}
