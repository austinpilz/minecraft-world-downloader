package proxy.accounts;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import packets.builder.PacketBuilder;
import packets.lib.ByteQueue;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPacketsTest {
    private static final String UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final byte[] UUID_BYTES = {
        0x06, (byte) 0x9a, 0x79, (byte) 0xf4, 0x44, (byte) 0xe9, 0x47, 0x26,
        (byte) 0xa5, (byte) 0xbe, (byte) 0xfc, (byte) 0xa9, 0x0e, 0x38, (byte) 0xaa, (byte) 0xf5
    };

    /**
     * The bytes of the packet after its length prefix and its id.
     */
    private static byte[] body(PacketBuilder builder) {
        byte[] all = builder.build().toArray();
        int offset = 0;
        while ((all[offset] & 0x80) != 0) { offset++; }
        offset++; // length
        return java.util.Arrays.copyOfRange(all, offset + 1, all.length);
    }

    private static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) { out.writeBytes(p); }
        return out.toByteArray();
    }

    private static byte[] string(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return cat(new byte[] {(byte) bytes.length}, bytes);
    }

    @Test
    void loginStartFor26_1HasNameAndUuid() {
        // 775 = 26.1
        assertThat(body(LoginPackets.loginStart(775, "Steve", UUID))).isEqualTo(cat(string("Steve"), UUID_BYTES));
    }

    @Test
    void loginStartFor1_20_2HasNameAndUuid() {
        assertThat(body(LoginPackets.loginStart(764, "Steve", UUID))).isEqualTo(cat(string("Steve"), UUID_BYTES));
    }

    @Test
    void loginStartFor1_19_3HasAnOptionalUuid() {
        assertThat(body(LoginPackets.loginStart(761, "Steve", UUID))).isEqualTo(cat(string("Steve"), new byte[] {1}, UUID_BYTES));
    }

    @Test
    void loginStartFor1_19_2HasNoSigningKeyAndAnOptionalUuid() {
        assertThat(body(LoginPackets.loginStart(760, "Steve", UUID))).isEqualTo(cat(string("Steve"), new byte[] {0, 1}, UUID_BYTES));
    }

    @Test
    void loginStartFor1_19HasNoSigningKey() {
        assertThat(body(LoginPackets.loginStart(759, "Steve", UUID))).isEqualTo(cat(string("Steve"), new byte[] {0}));
    }

    @Test
    void loginStartBefore1_19IsJustTheName() {
        assertThat(body(LoginPackets.loginStart(758, "Steve", UUID))).isEqualTo(string("Steve"));
    }

    @Test
    void encryptionResponseOnlyHasTheVerifyTokenFlagFor1_19To1_19_2() {
        byte[] secret = {1, 2, 3};
        byte[] token = {4, 5};

        assertThat(body(LoginPackets.encryptionResponse(775, secret, token))).isEqualTo(new byte[] {3, 1, 2, 3, 2, 4, 5});
        assertThat(body(LoginPackets.encryptionResponse(758, secret, token))).isEqualTo(new byte[] {3, 1, 2, 3, 2, 4, 5});
        assertThat(body(LoginPackets.encryptionResponse(760, secret, token))).isEqualTo(new byte[] {3, 1, 2, 3, 1, 2, 4, 5});
    }

    @Test
    void loginDisconnectIsATextComponent() {
        assertThat(new String(body(LoginPackets.loginDisconnect("No \"bots\" here")), StandardCharsets.UTF_8))
            .endsWith("{\"text\":\"No \\\"bots\\\" here\"}");
    }
}
