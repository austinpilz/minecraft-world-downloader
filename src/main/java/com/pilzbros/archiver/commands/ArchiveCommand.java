package com.pilzbros.archiver.commands;

import com.pilzbros.archiver.modules.ArchiveModule;
import com.pilzbros.archiver.modules.ZigZagArchiveModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;

/**
 * This the command handler for the archive addon. It will allow you to manually configure elements of the ongoing archive
 * session without needing to pause or restart the current leg.
 */
public class ArchiveCommand extends Command {

    private final ArchiveModule archiveModule;
    private final ZigZagArchiveModule zigZagArchiveModule;

    /**
     * The {@code name} parameter should be in kebab-case.
     */
    public ArchiveCommand(ArchiveModule archiveModule, ZigZagArchiveModule zigZagArchiveModule) {
        super("archive", "Archiver commands for world downloading.");

        this.archiveModule = archiveModule;
        this.zigZagArchiveModule = zigZagArchiveModule;
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("reset").executes(context -> {
            archiveModule.resetThings();
            zigZagArchiveModule.resetThings();
            info("Reset archiver attributes (spiral and zig-zag).");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("resume").executes(context -> {
            archiveModule.manuallySetChunkDistance();
            info("Manually set chunk distance. Ready to resume archiving.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("snap").executes(context -> {
            LocalPlayer player = Minecraft.getInstance().player;

            archiveModule.setPlayerYawTowardsDirection(player.getMotionDirection());
            archiveModule.movePlayerDirection(player.getMotionDirection(), player);
            info("Snapped player yaw to the true direction they're heading.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("rotate").executes(context -> {
            LocalPlayer player = Minecraft.getInstance().player;

            archiveModule.setPlayerYawTowardsDirection(archiveModule.getDirectionToTurnFromCurrentDirection(player.getMotionDirection()));
            archiveModule.movePlayerDirection(player.getMotionDirection(), player);
            info("Snapped player yaw to the true direction they're heading.");
            return SINGLE_SUCCESS;
        }));
    }
}
