package proxy.auth;

import com.google.gson.Gson;
import gui.GuiManager;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;

import config.Config;
import javax.security.sasl.AuthenticationException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import kong.unirest.json.JSONObject;
import proxy.EncryptionManager;
import proxy.SocksProxyDetails;

import static util.PrintUtils.devPrint;

/**
 * Handle authentication the client when joining servers.
 */
public class ClientAuthenticator extends AuthDetailsManager {
    private static final int STATUS_SUCCESS = 204;
    private static final String AUTH_URL = "https://sessionserver.mojang.com/session/minecraft/join";
    private static final String KEY_URL = "https://api.minecraftservices.com/player/certificates";

    public ClientAuthenticator() { }

    public ClientAuthenticator(String username) {
        super(username);
    }

    /**
     * Make the authentication request to the Mojang session server. We need to do this as the one sent by the
     * real client will have had our 'fake' public key instead of the server's real one, and as such the server will
     * not accept the connection.
     * @param hash hash based on the server information.
     */
    public void makeRequest(String hash) throws UnirestException, AuthenticationException {
        AuthDetails details;
        try {
            details = getAuthDetails();
        } catch (IOException e) {
            printAuthErrorMessage();
            throw new AuthenticationException("Cannot get valid authentication details.", e);
        }

        Map<String, String> body = new HashMap<>();

        body.put("accessToken", details.getAccessToken());
        body.put("selectedProfile", details.getUuid());
        body.put("serverId", hash);

        GuiManager.setStatusMessage("Authenticating with Mojang servers...");

        String json = new Gson().toJson(body);
        SocksProxyDetails socksProxy = Config.getSocksProxy();

        int status;
        String statusText;
        try {
            if (socksProxy == null) {
                HttpResponse<String> str = Unirest.post(AUTH_URL)
                        .header("Content-Type", "application/json")
                        .body(json)
                        .asString();
                status = str.getStatus();
                statusText = str.getStatusText();
            } else {
                // The session server records the IP that made the join, and the remote server passes the IP it sees
                // to hasJoined. When the game connection goes through SOCKS, the join has to come from the same IP.
                HttpURLConnection conn = (HttpURLConnection) new URL(AUTH_URL).openConnection(socksProxy.toProxy());
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                status = conn.getResponseCode();
                statusText = conn.getResponseMessage();
                conn.disconnect();
            }
        } catch (IOException e) {
            GuiManager.setStatusMessage("");
            GuiManager.setAuthenticationFailed();
            throw new RuntimeException("Could not reach Mojang session server: " + e.getMessage(), e);
        }

        GuiManager.setStatusMessage("");

        if (status < 200 || status >= 300) {
            GuiManager.setAuthenticationFailed();
            throw new RuntimeException("Client not authenticated! " + status + ": " + statusText);
        } else {
            devPrint("Successfully authenticated user with Mojang session server.");
        }
    }

    public void getClientProfileKeyPair(EncryptionManager em) throws AuthenticationException {
        AuthDetails details;
        try {
            details = getAuthDetails();
        } catch (IOException e) {
            printAuthErrorMessage();
            throw new AuthenticationException("Cannot get valid authentication details.", e);
        }

        GuiManager.setStatusMessage("Requesting encryption keys...");

        HttpResponse<JsonNode> res = Unirest.post(KEY_URL)
            .header("Authorization", "Bearer " + details.getAccessToken())
            .asJson();

        if (!res.isSuccess()) {
            GuiManager.setStatusMessage("");
            throw new RuntimeException("Cannot get client public key: " + res.getStatus() + ": " + res.getStatusText());
        }

        JsonNode json = res.getBody();

        JSONObject keyPair = json.getObject().getJSONObject("keyPair");
        String privateKey = keyPair.getString("privateKey");
        String publicKey = keyPair.getString("publicKey");

        // for some reason the key format doesn't appear to actually follow the specification? it
        // seems to follow the PKCS8 format but with PKCS1 begin/end, if we remove the "RSA" part
        // we can actually parse them normally
        privateKey = privateKey.replace("RSA ", "");
        publicKey = publicKey.replace("RSA ", "");

        em.setClientProfileKeyPair(privateKey, publicKey);

        GuiManager.setStatusMessage("");
    }
}
