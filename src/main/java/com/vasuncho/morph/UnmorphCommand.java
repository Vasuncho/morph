package com.vasuncho.morph;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /unmorph */
public final class UnmorphCommand implements TabExecutor {

    private final MorphManager manager;

    public UnmorphCommand(MorphManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MorphManager.error("Only players can use this command."));
            return true;
        }
        MorphData data = manager.morphData(player.getUniqueId());
        // A morphed player may always unmorph, even if de-opped since, so nobody gets stuck.
        if (data == null && !manager.isAuthorized(player)) {
            player.sendMessage(MorphManager.NOT_OP);
            return true;
        }
        if (manager.isPending(player.getUniqueId())) {
            player.sendMessage(MorphManager.ALREADY_PENDING);
            return true;
        }
        if (data == null) {
            player.sendMessage(MorphManager.error("You are not morphed."));
            return true;
        }
        if (manager.markPending(player.getUniqueId()) == null) {
            player.sendMessage(MorphManager.ALREADY_PENDING);
            return true;
        }
        manager.startUnmorphAnimation(player, data);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
