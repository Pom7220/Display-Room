package th.co.central.ris.bootlauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches fresh tokens from Cloudflare Worker via ROPC.
 * Called on boot before launching WebView.
 * Uses standard Java HttpURLConnection — no OkHttp dependency needed.
 */
public class TokenFetcher {

    private static final String TOKEN_URL =
        "https://ris-display.ris-display.workers.dev/api/token";

    public static class TokenResult {
        public boolean ok;
        public String accessToken;
        public String refreshToken;
        public String idToken;
        public String clientId;
        public int expiresIn;
        public String error;

        public boolean isValid() {
            return ok && accessToken != null && accessToken.length() > 0;
        }
    }

    /**
     * Fetch tokens synchronously — call from background thread only.
     */
    public static TokenResult fetchTokens(String adminKey) {
        TokenResult result = new TokenResult();
        try {
            URL url = new URL(TOKEN_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Admin-Key", adminKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            // Empty POST body — Worker uses stored secrets
            byte[] body = "{}".getBytes("UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();

            int code = conn.getResponseCode();
            BufferedReader br;
            if (code == 200) {
                br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            } else {
                java.io.InputStream es = conn.getErrorStream();
                if (es == null) {
                    result.ok = false;
                    result.error = "HTTP " + code;
                    return result;
                }
                br = new BufferedReader(new InputStreamReader(es));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String json = sb.toString();

            // Simple JSON parsing — no Gson needed
            if (json.contains("\"ok\":true")) {
                result.ok = true;
                result.accessToken = extractJson(json, "access_token");
                result.refreshToken = extractJson(json, "refresh_token");
                result.idToken = extractJson(json, "id_token");
                result.clientId = extractJson(json, "client_id");
                String expiresStr = extractJson(json, "expires_in");
                try { result.expiresIn = Integer.parseInt(expiresStr); } catch (Exception e) { result.expiresIn = 3600; }
            } else {
                result.ok = false;
                result.error = extractJson(json, "error");
                if (result.error == null) result.error = "HTTP " + code;
            }

        } catch (Exception e) {
            result.ok = false;
            result.error = e.getMessage();
        }
        return result;
    }

    /**
     * Simple JSON string value extractor — no library needed.
     */
    private static String extractJson(String json, String key) {
        try {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
