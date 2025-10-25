import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {
    // Demo showing OAuth2 Authorization Code flow for Dropbox and calling team endpoints.
    // Usage:
    //   java -cp out Main team-info
    //   java -cp out Main members
    //   java -cp out Main team-logs
    // If DROPBOX_ACCESS_TOKEN env var is set, the program will use it and skip the interactive auth flow.
    public static void main(String[] args) throws Exception {
        String mode = "team-info";
        if (args.length > 0) mode = args[0];

        String accessToken = System.getenv("DROPBOX_ACCESS_TOKEN");
        if (accessToken == null || accessToken.isEmpty()) {
            // perform interactive auth flow
            String clientId = System.getenv("DROPBOX_CLIENT_ID");
            String clientSecret = System.getenv("DROPBOX_CLIENT_SECRET");
            String redirectUri = System.getenv("DROPBOX_REDIRECT_URI");

            if (clientId == null || clientSecret == null || redirectUri == null) {
                System.out.println("Please set DROPBOX_ACCESS_TOKEN, or set DROPBOX_CLIENT_ID, DROPBOX_CLIENT_SECRET and DROPBOX_REDIRECT_URI (e.g. http://localhost:4567/oauth)");
                return;
            }

            accessToken = doAuthAndGetAccessToken(clientId, clientSecret, redirectUri);
            if (accessToken == null) {
                System.out.println("Could not obtain access token.");
                return;
            }
        } else {
            System.out.println("Using access token from DROPBOX_ACCESS_TOKEN environment variable.");
        }

        HttpClient client = HttpClient.newHttpClient();

        switch (mode) {
            case "team-info":
                callTeamInfo(client, accessToken);
                break;
            case "members":
                callMembersList(client, accessToken);
                break;
            case "team-logs":
                callTeamLogs(client, accessToken);
                break;
            default:
                System.out.println("Unknown mode: " + mode + "\nSupported modes: team-info, members, team-logs");
        }
    }

    private static String doAuthAndGetAccessToken(String clientId, String clientSecret, String redirectUri) throws Exception {
        // Start a tiny HTTP server to catch the redirect with code
        URI redirect = URI.create(redirectUri);
        int port = redirect.getPort() != -1 ? redirect.getPort() : 4567;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        final String[] codeHolder = {null};

        server.createContext(redirect.getPath(), (HttpExchange exchange) -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> q = splitQuery(query);
            String code = q.get("code");
            String responseText;
            if (code != null) {
                codeHolder[0] = code;
                responseText = "Authorization successful. You can close this window and return to the app.";
            } else {
                responseText = "Authorization failed or canceled. No code found.";
            }
            exchange.sendResponseHeaders(200, responseText.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseText.getBytes());
            }
        });

        server.start();
        System.out.println("Started local listener on port " + port + ", waiting for OAuth redirect...");

        String authUrl = "https://www.dropbox.com/oauth2/authorize" +
                "?client_id=" + urlEncode(clientId) +
                "&response_type=code" +
                "&token_access_type=offline" +
                "&redirect_uri=" + urlEncode(redirectUri);

        System.out.println("Open this URL in your browser to authorize the app:\n" + authUrl);
        try { java.awt.Desktop.getDesktop().browse(new URI(authUrl)); } catch (Exception ignored) {}

        // wait for code (timeout 5 minutes)
        int waited = 0;
        while (codeHolder[0] == null && waited < 300) {
            Thread.sleep(1000);
            waited++;
        }

        server.stop(0);

        if (codeHolder[0] == null) {
            System.out.println("No code received. Exiting.");
            return null;
        }

        String code = codeHolder[0];
        System.out.println("Received code: " + code);

        // Exchange code for token
        HttpClient client = HttpClient.newHttpClient();

        String tokenEndpoint = "https://api.dropbox.com/oauth2/token";

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", redirectUri);
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);

        String form = buildForm(params);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Token response status: " + tokenResp.statusCode());
        System.out.println(tokenResp.body());

        String accessToken = extractJsonString(tokenResp.body(), "access_token");
        return accessToken;
    }

    private static void callTeamInfo(HttpClient client, String accessToken) throws Exception {
        String teamInfoUrl = "https://api.dropboxapi.com/2/team/get_info";
        HttpRequest teamReq = HttpRequest.newBuilder()
                .uri(URI.create(teamInfoUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("null"))
                .build();

        HttpResponse<String> teamResp = client.send(teamReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Team info status: " + teamResp.statusCode());
        System.out.println(teamResp.body());
    }

    private static void callMembersList(HttpClient client, String accessToken) throws Exception {
        String url = "https://api.dropboxapi.com/2/team/members/list_v2";
        String body = "{ \"limit\": 100 }";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("Members list status: " + resp.statusCode());
        System.out.println(resp.body());
    }

    private static void callTeamLogs(HttpClient client, String accessToken) throws Exception {
        String url = "https://api.dropboxapi.com/2/team_log/get_events";
        // Simple body: last 90 days (example). You can customize by editing this method.
        String body = "{ \"limit\": 100 }";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("Team logs status: " + resp.statusCode());
        System.out.println(resp.body());
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> splitQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0) {
                    result.put(java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                            java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                } else {
                    result.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return result;
    }

    private static String buildForm(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    // Extremely small JSON extractor for simple string fields (not a full JSON parser)
    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int start = json.indexOf('"', colon);
        if (start == -1) return null;
        int end = json.indexOf('"', start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }
}
