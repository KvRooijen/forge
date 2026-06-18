package forge.headless.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Backs an AI seat. Java is the client here - synchronously POSTs the
 * decision context to the AI bridge and waits for the chosen action in
 * the response body. No correlation tracking needed since HTTP is
 * already request/response.
 */
public class HttpChannel implements RemoteChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final URI endpoint;

    public HttpChannel(String url) {
        this.endpoint = URI.create(url);
    }

    @Override
    public DecisionResponse ask(DecisionRequest request) {
        try {
            String body = MAPPER.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return MAPPER.readValue(response.body(), DecisionResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get decision over HTTP from " + endpoint, e);
        }
    }
}
