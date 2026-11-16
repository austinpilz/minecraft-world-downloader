package com.pilzbros.archiver.modules;

import com.pilzbros.archiver.ArchiverAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class HelperModule extends Module {
    private static final Logger LOG = Logger.getLogger("Minecraft");
    private static final String TUNING_LOG_HEADER = "timestamp,secondsConnected,ticksFlying,secondsFlying,tuning\n";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> timeWindowEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("Time Restrictions")
        .description("When enabled, will automatically disconnect you from the server when the configured time of day has been reached.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Integer> timeWindowHourSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Time Restriction Disconnect Hour")
        .description("The numerical hour in the day which when reached will disconnect the player.")
        .defaultValue(5)
        .min(0)
        .max(23)
        .sliderMax(23)
        .build()
    );

    private ArchiveModule archiveModule;

    private long sessionStartMillis;
    private long ticksConnectedThisSession;
    private long ticksFlyingThisSession;

    public HelperModule(ArchiveModule archiveModule) {
        super(ArchiverAddon.CATEGORY, "helper-module", "Automatic flying to archive the entire world.");
        this.archiveModule = archiveModule;
    }


    @EventHandler
    private void onTick(TickEvent.Post event) {

        if (archiveModule.isActive() && archiveModule.lastPlayer != null && !archiveModule.lastPlayer.equals(Minecraft.getInstance().player)) {
            archiveModule.toggle();
        }

        if (!archiveModule.isActive() && archiveModule.lastPlayer != null && !archiveModule.lastPlayer.equals(Minecraft.getInstance().player)) {

            archiveModule.toggle();
            archiveModule.resetLastPlayer();
        }

        if (timeWindowEnabled.get() && !isWithinAllowedTimePeriod()) {
            // We've reached the hour that we want to disconnect during.
            disconnect("Archiver Disconnect - Reached the configured disconnect hour.");
        }

        ticksConnectedThisSession++;

        if (archiveModule.isActive()) {
            ticksFlyingThisSession++;
        }
    }

    /**
     * Every test flight ends in a kick or a manual disconnect. Logging how long we lasted next to the tuning values
     * in play lets us compare attempts across sessions instead of relying on memory.
     */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        sessionStartMillis = System.currentTimeMillis();
        ticksConnectedThisSession = 0;
        ticksFlyingThisSession = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (sessionStartMillis == 0) {
            // We never actually joined a game this session (e.g. the addon just loaded), so there's nothing to log.
            return;
        }

        double secondsConnected = ticksConnectedThisSession / 20.0;
        double secondsFlying = ticksFlyingThisSession / 20.0;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        appendToTuningLog(String.format("%s,%.1f,%d,%.1f,\"%s\"%n",
            timestamp, secondsConnected, ticksFlyingThisSession, secondsFlying, archiveModule.describeTuning()));

        LOG.info("PilzBros Archiver >> Session ended after " + secondsConnected + "s connected (" + secondsFlying + "s flying). Logged to " + tuningLogPath());

        sessionStartMillis = 0;
    }

    private Path tuningLogPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("archiver-tuning-log.csv");
    }

    private void appendToTuningLog(String row) {
        try {
            Path path = tuningLogPath();

            if (Files.notExists(path)) {
                Files.writeString(path, TUNING_LOG_HEADER, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }

            Files.writeString(path, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("PilzBros Archiver >> Failed to write tuning log: " + e.getMessage());
        }
    }

    /**
     * Disconnects the current session from the server.
     */
    private void disconnect(String reason) {
        if (mc.player == null) return;

        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal(reason)));
    }

    private void doNothing(boolean setting) {
        //
    }

    /**
     * As long as it's not the hour configured, we're ok to keep going.
     */
    private boolean isWithinAllowedTimePeriod() {
        return LocalDateTime.now().getHour() != timeWindowHourSetting.get();
    }
}
