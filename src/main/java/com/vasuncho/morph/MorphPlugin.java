package com.vasuncho.morph;

import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class MorphPlugin extends JavaPlugin {

    private MorphManager manager;

    @Override
    public void onEnable() {
        manager = new MorphManager(this);
        ProfileFetcher fetcher = new ProfileFetcher(manager);

        MorphCommand morph = new MorphCommand(this, manager, fetcher);
        UnmorphCommand unmorph = new UnmorphCommand(manager);
        var morphCommand = Objects.requireNonNull(getCommand("morph"), "morph missing from plugin.yml");
        var unmorphCommand = Objects.requireNonNull(getCommand("unmorph"), "unmorph missing from plugin.yml");
        morphCommand.setExecutor(morph);
        morphCommand.setTabCompleter(morph);
        unmorphCommand.setExecutor(unmorph);
        unmorphCommand.setTabCompleter(unmorph);

        getServer().getPluginManager().registerEvents(new MorphListener(this, manager), this);
        getServer().getOnlinePlayers().forEach(manager::grantAllowlistPermission);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }
}
