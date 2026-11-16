package com.pilzbros.archiver.modules;

import com.pilzbros.archiver.ArchiverAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import java.util.List;
import java.util.Optional;

/**
 * This module handles
 *
 * - When a message is received from a player that we should avoid, we can disconnect the session. This can be in the form
 * of a chat message, direct message, or the server "X joined" messages that are broadcast to all players.
 * - When a player to avoid is detected to be on the server, we can disconnect the session.
 *
 * The staff usernames to avoid are server-specific, so they're a configurable setting rather than hardcoded - this
 * module gets reused across archival targets.
 */
public class ConflictAvoidanceModule extends Module {

    private static final String INTERROGATION_MESSAGE = "Stop trying to get out and respond to the interrogation!";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> disconnectOnAvoidPlayerMessageSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect on Avoid Player Message")
        .description("When enabled, the session to the server will be disconnected if we receive a message from a list of players to avoid being online with.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Boolean> disconnectOnAvoidPlayerPresenceSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect on Avoid Player Presence")
        .description("When enabled, the session to the server will be disconnected if we detect a player to avoid is currently online.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Integer> avoidPlayerPresenceTickDelaySetting = sgGeneral.add(new IntSetting.Builder()
        .name("Presence Detection Tick Delay")
        .description("The number of ticks to wait before we check the online player list for players to avoid.")
        .defaultValue(200)
        .min(5)
        .sliderMax(1000)
        .build()
    );

    private final Setting<List<String>> staffUsernamesSetting = sgGeneral.add(new StringListSetting.Builder()
        .name("Staff Usernames")
        .description("Usernames of staff/mods/admins on the target server to avoid being online with. Server-specific - set this per archival target.")
        .defaultValue()
        .build()
    );

    private int ticksSinceLastAvoidPresenceDetection;

    public ConflictAvoidanceModule() {
        super(ArchiverAddon.CATEGORY, "conflict-avoidance-module", "Automatically disconnect when specific players join.");
    }

    @EventHandler
    public void handleReceiveMessage(ReceiveMessageEvent event) {
        if (!disconnectOnAvoidPlayerMessageSetting.get()) {
            return;
        }

        if (event.getMessage().contains(Component.literal(INTERROGATION_MESSAGE))) {
            warning("Archiver Disconnect - You were hauled away by a f'in mod for interrogation. You're as good as banned. Disconnected session so you can manually switch accounts.");
            disconnect("Archiver Disconnect - Hauled away for interrogation.");
            return;
        }

        Optional<String> maybeUserToAvoid = staffUsernamesSetting.get().stream().filter(username -> event.getMessage().contains(Component.literal(username))).findFirst();

        if (maybeUserToAvoid.isPresent()) {
            // We're going to disconnect the session as there was a message from or about a player we wish to avoid being online with.
            warning("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " was detected in chat. Disconnected session to avoid detection/ban.");
            disconnect("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " detected in chat.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {

        if (disconnectOnAvoidPlayerPresenceSetting.get() && (ticksSinceLastAvoidPresenceDetection++ >= avoidPlayerPresenceTickDelaySetting.get())) {
           ticksSinceLastAvoidPresenceDetection = 0;

           if (mc.getConnection() == null) {
               // We don't yet have access to all the player's names on the server...
               return;
           }

            Optional<String> maybeUserToAvoid = mc.getConnection().getOnlinePlayers().stream()
                .map(playerInfo -> playerInfo.getProfile().name())
                .filter(name -> staffUsernamesSetting.get().contains(name))
                .findFirst();

            if (maybeUserToAvoid.isPresent()) {
                // We're going to disconnect the session as a player we want to avoid appears to be online.
                warning("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " was detected to be online in the player list. Disconnected session to avoid detection/ban.");
                disconnect("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " detected online.");
            }
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
    }
}
