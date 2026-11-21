package com.pilzbros.archiver.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pilzbros.archiver.ArchiverAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Posts to a Discord webhook when something happens that you'd want to know about while the archiver runs
 * unattended: Conflict Avoidance pulling you off the server, your username coming up in chat, or the connection
 * dropping for any other reason (kick, ban, timeout, the Helper module's time restriction).
 *
 * A Conflict Avoidance disconnect is reported once, as a Conflict Avoidance notice - the plain "disconnected"
 * notice that the same disconnect would otherwise also trigger is skipped. If the Conflict Avoidance checkbox is
 * off, that disconnect is still reported through the plain disconnect notice (when that one is on).
 */
public class NotifierModule extends Module {
    private static final String PREFIX = "PilzBros Notifier >> ";

    /** Discord rejects message content over 2000 characters. */
    private static final int DISCORD_MAX_CONTENT = 2000;

    /**
     * Ignore mentions for this long after joining, so the server's own "YourName joined the game" / "Welcome,
     * YourName" messages don't send a notification on every reconnect.
     */
    private static final long MENTION_GRACE_PERIOD_MILLIS = 10_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> webhookUrlSetting = sgGeneral.add(new StringSetting.Builder()
        .name("Discord Webhook URL")
        .description("The Discord channel webhook to post notifications to (Channel Settings > Integrations > Webhooks > Copy Webhook URL).")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> notifyOnConflictAvoidanceSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify on Conflict Avoidance Disconnect")
        .description("Send a message when the Conflict Avoidance module disconnects you, for any of its reasons.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnMentionSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify When Username Mentioned")
        .description("Send a message (with the chat line) when your username appears in chat. Case-insensitive, whole-name matches only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnDisconnectSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify on Disconnect")
        .description("Send a message, with the server's reason, whenever you're disconnected from the server for any reason - kicks, bans, timeouts, and the Helper module's time restriction.")
        .defaultValue(true)
        .build()
    );

    /** Set when a Conflict Avoidance notice was sent, so the disconnect it causes isn't reported a second time. */
    private volatile boolean suppressNextDisconnectNotice = false;

    private long joinedAtMillis = 0;

    /** How long after sending a chat message its echo from the server is still ignored by the mention check. */
    private static final long OWN_MESSAGE_ECHO_WINDOW_MILLIS = 15_000;

    private record SentMessage(String text, long sentAtMillis) {}

    private final List<SentMessage> recentOwnMessages = new ArrayList<>();

    /** Last known position, so a disconnect notice can say where you were when it happened. */
    private String lastKnownPosition = null;

    public NotifierModule() {
        super(ArchiverAddon.CATEGORY, "notifier-module", "Sends Discord webhook notifications for disconnects and chat mentions, for running unattended.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton testButton = theme.button("Send Test Message");
        testButton.action = () -> send("Test message from the World Archiver notifier" + accountSuffix() + ". If you can see this, notifications are set up.", true);
        return testButton;
    }

    @Override
    public void onActivate() {
        suppressNextDisconnectNotice = false;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        joinedAtMillis = System.currentTimeMillis();
        suppressNextDisconnectNotice = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player != null) {
            lastKnownPosition = String.format("%d, %d, %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!notifyOnMentionSetting.get() || mc.player == null) return;
        if (System.currentTimeMillis() - joinedAtMillis < MENTION_GRACE_PERIOD_MILLIS) return;

        String username = mc.player.getGameProfile().name();
        String text = event.getMessage().getString();

        // Whole-name match, so "Bob" doesn't fire on "Bobby" - Minecraft names are letters, digits and underscores.
        Pattern mention = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(username) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);

        if (mention.matcher(text).find() && !isEchoOfOwnMessage(text)) {
            send(":speech_balloon: **Mentioned in chat**" + accountSuffix() + "\n" + quote(text), false);
        }
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        rememberOwnMessage(event.message);
    }

    /**
     * Records chat this client sent itself, so the server echoing it back ("&lt;YourName&gt; hello") isn't reported
     * as someone mentioning you. Called for typed chat via {@link SendMessageEvent}, and directly by
     * {@link ConflictAvoidanceModule} for its goodbye message.
     */
    public static void noteOwnChatMessage(String message) {
        NotifierModule notifier = Modules.get().get(NotifierModule.class);
        if (notifier != null) notifier.rememberOwnMessage(message);
    }

    private void rememberOwnMessage(String message) {
        if (message == null || message.isBlank()) return;

        long now = System.currentTimeMillis();
        recentOwnMessages.removeIf(sent -> now - sent.sentAtMillis() > OWN_MESSAGE_ECHO_WINDOW_MILLIS);
        recentOwnMessages.add(new SentMessage(message.strip(), now));
    }

    private boolean isEchoOfOwnMessage(String receivedText) {
        long now = System.currentTimeMillis();
        return recentOwnMessages.stream()
            .anyMatch(sent -> now - sent.sentAtMillis() <= OWN_MESSAGE_ECHO_WINDOW_MILLIS && receivedText.contains(sent.text()));
    }

    /**
     * Called by {@link ConflictAvoidanceModule} right before it disconnects you.
     */
    public static void notifyConflictAvoidanceDisconnect(String reason, String chatMessageSent) {
        NotifierModule notifier = Modules.get().get(NotifierModule.class);
        if (notifier == null || !notifier.isActive() || !notifier.notifyOnConflictAvoidanceSetting.get()) return;

        String saidLine = chatMessageSent == null ? "" : "\nSaid in chat before leaving: `" + chatMessageSent.replace("`", "'") + "`";

        notifier.suppressNextDisconnectNotice = true;
        notifier.send(":rotating_light: **Conflict Avoidance disconnected you**" + notifier.accountSuffix() + "\n"
            + quote(reason) + saidLine + notifier.positionLine(), false);
    }

    /**
     * Called from the disconnect mixin whenever the client's connection to the server ends, with the reason shown
     * on the disconnect screen.
     */
    public static void onConnectionLost(Component reason) {
        NotifierModule notifier = Modules.get().get(NotifierModule.class);
        if (notifier == null || !notifier.isActive()) return;

        // Only report losing an in-game session - not a failed join during the configuration phase, which an
        // auto-reconnect loop could otherwise turn into a stream of notifications.
        if (notifier.mc.level == null) return;

        if (notifier.suppressNextDisconnectNotice) {
            notifier.suppressNextDisconnectNotice = false;
            return;
        }

        if (!notifier.notifyOnDisconnectSetting.get()) return;

        notifier.send(":electric_plug: **Disconnected from the server**" + notifier.accountSuffix() + "\n"
            + quote(reason.getString()) + notifier.positionLine(), false);
    }

    private String accountSuffix() {
        StringBuilder suffix = new StringBuilder();

        if (mc.player != null) {
            suffix.append(" - `").append(mc.player.getGameProfile().name()).append("`");
        } else {
            suffix.append(" - `").append(mc.getUser().getName()).append("`");
        }

        ServerData server = mc.getCurrentServer();
        if (server != null) {
            suffix.append(" on `").append(server.ip).append("`");
        }

        return suffix.toString();
    }

    private String positionLine() {
        return lastKnownPosition == null ? "" : "\nLast position: `" + lastKnownPosition + "`";
    }

    /** Quote chat/reason text so it's set apart from our own formatting; multi-line text stays inside the quote. */
    private static String quote(String text) {
        return "> " + text.strip().replace("\n", "\n> ");
    }

    /**
     * Posts {@code content} to the webhook in the background, so a slow or unreachable Discord never stalls the
     * game. Failures are logged; {@code reportInChat} also shows the result in chat (used by the test button).
     */
    private void send(String content, boolean reportInChat) {
        String url = webhookUrlSetting.get().strip();

        if (url.isEmpty() || !url.startsWith("https://") || !url.contains("/api/webhooks/")) {
            if (reportInChat) warning("Set a valid Discord Webhook URL first (it should contain /api/webhooks/).");
            else ArchiverAddon.LOG.warn(PREFIX + "Not sending notification - no valid Discord webhook URL is set.");
            return;
        }

        if (content.length() > DISCORD_MAX_CONTENT) {
            content = content.substring(0, DISCORD_MAX_CONTENT - 3) + "...";
        }

        JsonObject body = new JsonObject();
        body.addProperty("username", "World Archiver");
        body.addProperty("content", content);

        // Chat text comes from other players - never let it ping @everyone, @here, users or roles.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        } catch (IllegalArgumentException e) {
            if (reportInChat) warning("The Discord Webhook URL isn't a valid URL.");
            else ArchiverAddon.LOG.warn(PREFIX + "Not sending notification - the Discord webhook URL is invalid.");
            return;
        }

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String failure = null;

            if (error != null) {
                failure = "Couldn't reach Discord: " + error.getMessage();
            } else if (response.statusCode() / 100 != 2) {
                failure = "Discord rejected the notification (HTTP " + response.statusCode() + "): " + response.body();
            }

            if (failure != null) {
                ArchiverAddon.LOG.warn(PREFIX + failure);
                if (reportInChat) {
                    String message = failure;
                    mc.execute(() -> warning(message));
                }
            } else if (reportInChat) {
                mc.execute(() -> info("Test message sent."));
            }
        });
    }
}
