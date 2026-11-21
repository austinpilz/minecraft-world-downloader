package com.pilzbros.archiver.modules;

import com.pilzbros.archiver.ArchiverAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

    private final Setting<List<String>> staffUsernamesSetting = sgGeneral.add(new StringListSetting.Builder()
        .name("Staff Usernames")
        .description("Usernames of staff/mods/admins on the target server to avoid being online with. Exact match, case-sensitive. Server-specific - set this per archival target.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> disconnectOnAvoidPlayerMessageSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect When Staff Mentioned in Chat")
        .description("Disconnect if any chat message (including join messages and DMs) contains one of the Staff Usernames, or if you're pulled in for interrogation.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Boolean> disconnectOnAvoidPlayerPresenceSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect When Staff Online")
        .description("Disconnect if any of the Staff Usernames appears in the online player list. Does nothing while Staff Usernames is empty.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Boolean> disconnectOnAnyOtherPlayerPresenceSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect When Anyone Else Online")
        .description("Disconnect if any player other than you appears in the online player list, regardless of Staff Usernames.")
        .defaultValue(false)
        .onChanged(this::doNothing)
        .build()
    );

    private final Setting<Integer> avoidPlayerPresenceTickDelaySetting = sgGeneral.add(new IntSetting.Builder()
        .name("Online Check Interval (Ticks)")
        .description("How often, in ticks, to check the online player list for the two \"Online\" options above. 20 ticks = 1 second.")
        .defaultValue(200)
        .min(5)
        .sliderMax(1000)
        .build()
    );

    private final Setting<List<String>> disconnectChatMessagesSetting = sgGeneral.add(new StringListSetting.Builder()
        .name("Disconnect Chat Messages")
        .description("When Conflict Avoidance disconnects you, first say one of these (picked at random) in chat, e.g. \"oh RIP, I was just heading out\". Leave empty to disconnect silently.")
        .defaultValue()
        .build()
    );

    /**
     * How long to wait between saying the disconnect chat message and actually disconnecting. Disconnecting in the
     * same tick risks the chat packet never leaving before the connection closes, and an instant exit right after
     * talking reads less naturally anyway.
     */
    private static final int CHAT_MESSAGE_DISCONNECT_DELAY_TICKS = 20;

    private int ticksSinceLastAvoidPresenceDetection;

    /** A disconnect that's waiting for the goodbye chat message to go out, or null when none is pending. */
    private String pendingDisconnectReason;
    private String pendingDisconnectChatMessage;
    private int ticksUntilPendingDisconnect;

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

    @Override
    public void onActivate() {
        clearPendingDisconnect();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearPendingDisconnect();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (pendingDisconnectReason != null) {
            // Already leaving - just wait out the delay after the goodbye message, then go.
            if (--ticksUntilPendingDisconnect <= 0) {
                String reason = pendingDisconnectReason;
                String chatMessage = pendingDisconnectChatMessage;
                clearPendingDisconnect();
                disconnectNow(reason, chatMessage);
            }

            return;
        }

        boolean checkStaff = disconnectOnAvoidPlayerPresenceSetting.get();
        boolean checkAnyone = disconnectOnAnyOtherPlayerPresenceSetting.get();

        if ((checkStaff || checkAnyone) && (ticksSinceLastAvoidPresenceDetection++ >= avoidPlayerPresenceTickDelaySetting.get())) {
            ticksSinceLastAvoidPresenceDetection = 0;

            if (mc.getConnection() == null || mc.player == null) {
                // We don't yet have access to all the player's names on the server...
                return;
            }

            UUID selfId = mc.player.getUUID();
            List<String> otherPlayerNames = mc.getConnection().getOnlinePlayers().stream()
                .filter(playerInfo -> !selfId.equals(playerInfo.getProfile().id()))
                .map(playerInfo -> playerInfo.getProfile().name())
                .toList();

            if (checkStaff) {
                Optional<String> maybeUserToAvoid = otherPlayerNames.stream()
                    .filter(name -> staffUsernamesSetting.get().contains(name))
                    .findFirst();

                if (maybeUserToAvoid.isPresent()) {
                    // We're going to disconnect the session as a player we want to avoid appears to be online.
                    warning("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " was detected to be online in the player list. Disconnected session to avoid detection/ban.");
                    disconnect("Archiver Disconnect - Player to avoid " + maybeUserToAvoid.get() + " detected online.");
                    return;
                }
            }

            if (checkAnyone && !otherPlayerNames.isEmpty()) {
                // Anyone else at all could witness the flying, so leave before they get a chance to.
                String otherPlayer = otherPlayerNames.getFirst();
                warning("Archiver Disconnect - Another player (" + otherPlayer + ") was detected to be online in the player list. Disconnected session to avoid detection/ban.");
                disconnect("Archiver Disconnect - Another player (" + otherPlayer + ") detected online.");
            }
        }
    }

    /**
     * Disconnects the current session from the server - after saying a random goodbye from Disconnect Chat Messages
     * first, if any are configured.
     */
    private void disconnect(String reason) {
        if (mc.player == null || pendingDisconnectReason != null) return;

        List<String> chatMessages = disconnectChatMessagesSetting.get().stream()
            .filter(message -> !message.isBlank())
            .toList();

        if (chatMessages.isEmpty()) {
            disconnectNow(reason, null);
            return;
        }

        String chatMessage = chatMessages.get(ThreadLocalRandom.current().nextInt(chatMessages.size()));
        NotifierModule.noteOwnChatMessage(chatMessage);
        ChatUtils.sendPlayerMsg(chatMessage);

        pendingDisconnectReason = reason;
        pendingDisconnectChatMessage = chatMessage;
        ticksUntilPendingDisconnect = CHAT_MESSAGE_DISCONNECT_DELAY_TICKS;
    }

    private void disconnectNow(String reason, String chatMessageSent) {
        if (mc.player == null) return;

        NotifierModule.notifyConflictAvoidanceDisconnect(reason, chatMessageSent);
        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal(reason)));
    }

    private void clearPendingDisconnect() {
        pendingDisconnectReason = null;
        pendingDisconnectChatMessage = null;
        ticksUntilPendingDisconnect = 0;
    }

    private void doNothing(boolean setting) {
    }
}
