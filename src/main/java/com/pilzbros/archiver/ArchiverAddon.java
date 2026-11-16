package com.pilzbros.archiver;

import com.pilzbros.archiver.commands.ArchiveCommand;
import com.pilzbros.archiver.hud.HudExample;
import com.pilzbros.archiver.modules.ArchiveModule;
import com.pilzbros.archiver.modules.ConflictAvoidanceModule;
import com.pilzbros.archiver.modules.HelperModule;
import com.pilzbros.archiver.modules.ZigZagArchiveModule;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ArchiverAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Archiver");
    public static final HudGroup HUD_GROUP = new HudGroup("Archiver");

    @Override
    public void onInitialize() {
        // Modules
        ArchiveModule archiveModule = new ArchiveModule();
        ZigZagArchiveModule zigZagArchiveModule = new ZigZagArchiveModule();

        Modules.get().add(archiveModule);
        Modules.get().add(new HelperModule(archiveModule));
        Modules.get().add(zigZagArchiveModule);
        Modules.get().add(new ConflictAvoidanceModule());

        // Commands
        Commands.add(new ArchiveCommand(archiveModule, zigZagArchiveModule));

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.pilzbros.archiver";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("austinpilz", "Minecraft-World-Downloader-Mod");
    }
}
