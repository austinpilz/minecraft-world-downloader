package proxy;

import java.util.function.IntFunction;

/**
 * Keeps every bot of a fleet on the same game version. The world is written for one version, and the packets of all
 * connections are read with the protocol of the first bot that logged in, so a bot on another version is refused.
 */
public class ProtocolLock {
    /**
     * What to do with a handshake.
     * @param setVersion whether the proxy should switch to the handshake's version
     * @param refusal    why the client may not log in, or null if it may
     */
    public record Decision(boolean setVersion, String refusal) { }

    private final IntFunction<String> versionName;
    private int locked = -1;

    /**
     * @param versionName gives the game version for a protocol version, for the refusal message
     */
    public ProtocolLock(IntFunction<String> versionName) {
        this.versionName = versionName;
    }

    /**
     * A client sent a handshake. Until a bot logs in any version is fine, the first login locks it.
     * @param login true if the client wants to log in, false for a status ping
     */
    public synchronized Decision handshake(int protocolVersion, boolean login) {
        if (locked < 0) {
            if (login) {
                locked = protocolVersion;
            }
            return new Decision(true, null);
        }

        if (locked == protocolVersion || !login) {
            return new Decision(false, null);
        }
        return new Decision(false, "The other bots are on " + versionName.apply(locked) + " (protocol " + locked
            + "), connect with that version. This client is on " + versionName.apply(protocolVersion)
            + " (protocol " + protocolVersion + ").");
    }

    /**
     * The protocol version bots have to use, or -1 if no bot has logged in yet.
     */
    public synchronized int getLocked() {
        return locked;
    }
}
