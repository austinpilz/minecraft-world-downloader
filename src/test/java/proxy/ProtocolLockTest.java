package proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolLockTest {
    private final ProtocolLock lock = new ProtocolLock(v -> v == 775 ? "26.1" : "1.21.4");

    @Test
    void firstLoginLocksTheVersion() {
        assertThat(lock.handshake(769, false)).isEqualTo(new ProtocolLock.Decision(true, null));
        assertThat(lock.getLocked()).isEqualTo(-1);

        assertThat(lock.handshake(775, true)).isEqualTo(new ProtocolLock.Decision(true, null));
        assertThat(lock.getLocked()).isEqualTo(775);

        assertThat(lock.handshake(775, true)).isEqualTo(new ProtocolLock.Decision(false, null));
    }

    @Test
    void otherVersionsAreRefusedOnceLocked() {
        lock.handshake(775, true);

        ProtocolLock.Decision decision = lock.handshake(769, true);
        assertThat(decision.setVersion()).isFalse();
        assertThat(decision.refusal()).contains("26.1 (protocol 775)").contains("1.21.4 (protocol 769)");
        assertThat(lock.getLocked()).isEqualTo(775);
    }

    @Test
    void statusPingsDontChangeTheLockedVersion() {
        lock.handshake(775, true);

        assertThat(lock.handshake(769, false)).isEqualTo(new ProtocolLock.Decision(false, null));
        assertThat(lock.getLocked()).isEqualTo(775);
    }
}
