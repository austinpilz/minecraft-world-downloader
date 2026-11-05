package proxy.accounts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import config.Config;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packets.builder.PacketBuilder;
import packets.lib.ByteQueue;
import proxy.CompressionManager;
import proxy.EncryptionManager;
import proxy.SocksProxyDetails;
import proxy.auth.AuthDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plays the server's side of a login against the proxy's answer for a proxy-held account.
 */
class BotEncryptionTest {
    private static final String SERVER_ID = "";

    private TestHttpServer session;
    private TestSocks5Server socks;
    private KeyPair serverKeys;
    private EncryptionManager manager;
    private ByteArrayOutputStream wire;

    @BeforeEach
    void setUp() throws Exception {
        Config.setInstance(new Config());

        session = new TestHttpServer("", 204);
        socks = new TestSocks5Server("svc", "pw");
        System.setProperty("mwd.sessionServer", "http://127.0.0.1:" + session.port());

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        serverKeys = gen.generateKeyPair();

        manager = new EncryptionManager(new CompressionManager());
        wire = new ByteArrayOutputStream();
        manager.setStreamToServer(wire);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("mwd.sessionServer");
        session.close();
        socks.close();
    }

    private byte[] decryptRsa(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, serverKeys.getPrivate());
        return cipher.doFinal(data);
    }

    private static String minecraftHash(byte[] secret, byte[] publicKey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(SERVER_ID.getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(secret);
        sha1.update(publicKey);
        return new BigInteger(sha1.digest()).toString(16);
    }

    /**
     * Read the packet the proxy wrote: length, id 0x01, then the arrays.
     */
    private record Response(byte[] secret, byte[] token) { }

    private Response readResponse(boolean verifyTokenFlag) throws Exception {
        byte[] all = wire.toByteArray();
        int[] pos = {0};
        int length = varInt(all, pos);
        assertThat(all.length - pos[0]).isEqualTo(length);
        assertThat(all[pos[0]++]).isEqualTo((byte) 0x01);

        int secretLength = varInt(all, pos);
        byte[] secret = Arrays.copyOfRange(all, pos[0], pos[0] + secretLength);
        pos[0] += secretLength;
        if (verifyTokenFlag) {
            assertThat(all[pos[0]++]).isEqualTo((byte) 1);
        }
        int tokenLength = varInt(all, pos);
        byte[] token = Arrays.copyOfRange(all, pos[0], pos[0] + tokenLength);
        assertThat(pos[0] + tokenLength).isEqualTo(all.length);
        return new Response(decryptRsa(secret), decryptRsa(token));
    }

    private static int varInt(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = data[pos[0]++];
            value |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    @Test
    void answersWithTheBotsSecretAndTheServersNonceThroughTheExit() throws Exception {
        byte[] nonce = {9, 8, 7, 6};
        SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", socks.port(), "svc", "pw").pin();
        AuthDetails bot = new AuthDetails("Alice", "00000000-0000-0000-0000-000000000001", "bot-access-token");

        manager.answerEncryptionRequest(serverKeys.getPublic().getEncoded(), nonce, SERVER_ID, true, bot, exit, 775);

        Response response = readResponse(false);
        assertThat(response.token()).isEqualTo(nonce);
        assertThat(response.secret()).hasSize(16);

        // the join went out through the exit, as the bot, with the hash of the secret the server just received
        assertThat(socks.loggedInUsers).containsExactly("svc");
        assertThat(socks.targets).containsExactly("127.0.0.1:" + session.port());
        assertThat(session.postBodies).hasSize(1);
        JsonObject join = JsonParser.parseString(session.postBodies.get(0)).getAsJsonObject();
        assertThat(join.get("accessToken").getAsString()).isEqualTo("bot-access-token");
        assertThat(join.get("selectedProfile").getAsString()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(join.get("serverId").getAsString())
            .isEqualTo(minecraftHash(response.secret(), serverKeys.getPublic().getEncoded()));
    }

    @Test
    void onlyTheServerLegIsEncryptedAfterwards() throws Exception {
        AuthDetails bot = new AuthDetails("Alice", "uuid", "token");
        manager.answerEncryptionRequest(serverKeys.getPublic().getEncoded(), new byte[] {1}, SERVER_ID, true, bot, null, 775);
        Response response = readResponse(false);

        assertThat(manager.isServerLegEncrypted()).isTrue();
        assertThat(manager.isClientLegEncrypted()).isFalse();

        // everything sent to the server from now on is AES/CFB8 with the secret as both key and IV
        wire.reset();
        byte[] plain = {0x03, 0x02, 0x00, 0x00, 0x55, 0x66, 0x77};
        manager.streamToServer(new ByteQueue(plain));

        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(response.secret(), "AES"), new IvParameterSpec(response.secret()));
        assertThat(cipher.doFinal(wire.toByteArray())).isEqualTo(plain);
    }

    @Test
    void protocols1_19To1_19_2CarryTheVerifyTokenFlag() throws Exception {
        AuthDetails bot = new AuthDetails("Alice", "uuid", "token");
        SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", socks.port(), "svc", "pw").pin();

        manager.answerEncryptionRequest(serverKeys.getPublic().getEncoded(), new byte[] {5, 5}, SERVER_ID, true, bot, exit, 760);

        assertThat(readResponse(true).token()).isEqualTo(new byte[] {5, 5});
    }

    @Test
    void doesNotJoinWhenTheServerIsInOfflineMode() throws Exception {
        AuthDetails bot = new AuthDetails("Alice", "uuid", "token");

        manager.answerEncryptionRequest(serverKeys.getPublic().getEncoded(), new byte[] {1}, SERVER_ID, false, bot, null, 775);

        assertThat(session.postBodies).isEmpty();
        assertThat(manager.isServerLegEncrypted()).isTrue();
    }

    @Test
    void aRejectedJoinFailsLoudlyAndSendsNothing() throws Exception {
        try (TestHttpServer rejecting = new TestHttpServer("", 403)) {
            System.setProperty("mwd.sessionServer", "http://127.0.0.1:" + rejecting.port());
            AuthDetails bot = new AuthDetails("Alice", "uuid", "expired-token");

            assertThatThrownBy(() -> manager.answerEncryptionRequest(
                serverKeys.getPublic().getEncoded(), new byte[] {1}, SERVER_ID, true, bot, null, 775))
                .hasMessageContaining("403");

            assertThat(wire.size()).isZero();
            assertThat(manager.isServerLegEncrypted()).isFalse();
        }
    }
}
