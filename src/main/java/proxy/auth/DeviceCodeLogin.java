package proxy.auth;

import java.util.function.BooleanSupplier;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

/**
 * Microsoft login with a device code: the user enters a short code at microsoft.com/link, in any browser, and logs
 * in to the account there. Unlike the normal login this needs no local web server and no browser on this machine,
 * and it can be done for several accounts one after another without their browser sessions getting mixed up.
 */
public class DeviceCodeLogin {
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

    private final String userCode;
    private final String deviceCode;
    private final String verificationUri;
    private final int interval;
    private final long expiresAt;

    private DeviceCodeLogin(JSONObject jso) {
        this.userCode = jso.getString("user_code");
        this.deviceCode = jso.getString("device_code");
        this.verificationUri = jso.getString("verification_uri");
        this.interval = Math.max(1, jso.optInt("interval", 5));
        this.expiresAt = System.currentTimeMillis() + jso.getInt("expires_in") * 1000L;
    }

    /**
     * Ask Microsoft for a new code.
     */
    public static DeviceCodeLogin start() {
        HttpResponse<JsonNode> res = Unirest.post(DEVICE_CODE_URL)
            .contentType("application/x-www-form-urlencoded")
            .field("client_id", MicrosoftAuthHandler.CLIENT_ID)
            .field("scope", MicrosoftAuthHandler.SCOPE)
            .asJson();

        if (!res.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot get a login code from Microsoft. Status: " + res.getStatus());
        }
        return new DeviceCodeLogin(res.getBody().getObject());
    }

    /**
     * The code the user has to enter.
     */
    public String getUserCode() {
        return userCode;
    }

    /**
     * Where the user enters the code, normally https://www.microsoft.com/link.
     */
    public String getVerificationUri() {
        return verificationUri;
    }

    /**
     * Wait for the user to finish logging in, then get the account's Minecraft tokens. Blocks, so call this from a
     * background thread.
     * @param cancelled checked between polls, stops waiting when it returns true
     * @return the handler for the account, or null if cancelled
     */
    public MicrosoftAuthHandler await(BooleanSupplier cancelled) throws InterruptedException {
        long wait = interval * 1000L;

        while (System.currentTimeMillis() < expiresAt) {
            Thread.sleep(wait);
            if (cancelled.getAsBoolean()) {
                return null;
            }

            HttpResponse<JsonNode> res = Unirest.post(MicrosoftAuthHandler.DEVICE_TOKEN_URL)
                .contentType("application/x-www-form-urlencoded")
                .field("client_id", MicrosoftAuthHandler.CLIENT_ID)
                .field("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .field("device_code", deviceCode)
                .asJson();

            JSONObject jso = res.getBody() == null ? new JSONObject() : res.getBody().getObject();
            if (res.isSuccess()) {
                return MicrosoftAuthHandler.fromDeviceFlow(
                    jso.getString("access_token"),
                    jso.getString("refresh_token"),
                    jso.getInt("expires_in")
                );
            }

            String error = jso.optString("error", "status " + res.getStatus());
            switch (error) {
                case "authorization_pending" -> { }
                case "slow_down" -> wait += 5000;
                case "authorization_declined" -> throw new MinecraftAuthenticationException("The login was declined.");
                case "expired_token" -> throw new MinecraftAuthenticationException("The login code expired, start again.");
                default -> throw new MinecraftAuthenticationException("Microsoft login failed: " + error);
            }
        }
        throw new MinecraftAuthenticationException("The login code expired, start again.");
    }
}
