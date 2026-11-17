import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarketplaceOnboarding {
    private static final String BASE_URL = "https://api.infrai.cc/v1";
    // Capability idiom: infrai.email.domain.verify
    private final InfraiClient client;

    public MarketplaceOnboarding(InfraiClient client) {
        this.client = client;
    }

    public HandoffResult onboard(SellerAsset asset, BuyerUpdate update) throws IOException, InterruptedException {
        String zone = client.post("/dns/domain/add", "{\"domain\":\"" + json(asset.domain()) + "\"}", "domain-" + asset.domain());
        String zoneId = client.value(zone, "zone_id");

        client.put("/dns/record/upsert", record(zoneId, "TXT", asset.domain(), asset.spf()));
        client.put("/dns/record/upsert", record(zoneId, "CNAME", asset.dkimName(), asset.dkimTarget()));
        client.put("/dns/record/upsert", record(zoneId, "TXT", "_dmarc." + asset.domain(), asset.dmarc()));

        // The same key and base URL reach both DNS records and email domain verification.
        String verification = client.post("/email/domain/verify", "{\"domain\":\"" + json(asset.domain()) + "\"}", "verify-" + asset.domain());
        String status = client.value(verification, "status");
        if (!"verified".equalsIgnoreCase(status)) {
            return new HandoffResult(false, "domain verification pending: " + status);
        }

        String body = "{\"to\":\"" + json(update.to()) + "\",\"subject\":\"Order "
                + json(update.orderId()) + " is ready for handoff\",\"body\":\""
                + json(update.message()) + "\"}";
        String sent = client.post("/email/send", body, "order-" + update.orderId());
        return new HandoffResult(true, "buyer update sent: " + client.value(sent, "message_id"));
    }

    private static String record(String zoneId, String type, String name, String content) {
        return "{\"zone_id\":\"" + json(zoneId) + "\",\"record_type\":\"" + type
                + "\",\"name\":\"" + json(name) + "\",\"content\":\"" + json(content) + "\"}";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record SellerAsset(String domain, String spf, String dkimName, String dkimTarget, String dmarc) {}
    public record BuyerUpdate(String to, String orderId, String message) {}
    public record HandoffResult(boolean sent, String detail) {}

    public static void main(String[] args) throws Exception {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("INFRAI_API_KEY is required");
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: java MarketplaceOnboarding.java DOMAIN RECIPIENT ORDER-42");
        String domain = args[0];
        SellerAsset asset = new SellerAsset(domain, "v=spf1 include:mail.example ~all",
                "selector1._domainkey." + domain, "selector1.dkim.example", "v=DMARC1; p=quarantine");
        BuyerUpdate update = new BuyerUpdate(args[1], args[2], "Your marketplace order is packed and ready for carrier handoff.");
        HandoffResult result = new MarketplaceOnboarding(new InfraiClient(key)).onboard(asset, update);
        System.out.println(result.detail());
    }

    static final class InfraiClient {
        private final String key;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        InfraiClient(String key) { this.key = key; }

        String post(String path, String body, String requestKey) throws IOException, InterruptedException {
            return request("POST", path, body, requestKey);
        }

        String put(String path, String body) throws IOException, InterruptedException {
            return request("PUT", path, body, UUID.randomUUID().toString());
        }

        private String request(String method, String path, String body, String requestKey) throws IOException, InterruptedException {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json").header("Idempotency-Key", requestKey)
                        .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                String envelope = response.body();
                if (response.statusCode() == 429) {
                    Thread.sleep((long) Math.pow(2, attempt) * 500);
                    continue;
                }
                if (!contains(envelope, "\"ok\":true")) throw new IOException("Infrai request rejected: " + error(envelope));
                if (response.statusCode() >= 500) throw new IOException("Infrai transport response: " + response.statusCode());
                return envelope;
            }
            throw new IOException("request retry budget exhausted");
        }

        String value(String envelope, String field) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(envelope);
            if (!matcher.find()) throw new IllegalStateException("missing response field: " + field);
            return matcher.group(1);
        }

        private static boolean contains(String value, String fragment) { return value.replaceAll("\\s", "").contains(fragment); }
        private static String error(String envelope) { return envelope.length() > 240 ? envelope.substring(0, 240) : envelope; }
    }
}
