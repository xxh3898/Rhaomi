package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rhaomi.build-service.token=")
@ActiveProfiles("test")
class BuildServiceDisabledContractTests {

    @LocalServerPort
    private int port;

    @Test
    void should_failClosedOnlyBuildNamespace_when_nonProductionTokenIsDisabled() throws Exception {
        var client = HttpClient.newHttpClient();
        var build = get(client, "/api/build/snapshot?publishGeneration=1", "Bearer " + "a".repeat(64));

        assertEquals(503, build.statusCode());
        assertTrue(build.body().contains("\"code\":\"BUILD_SERVICE_UNAVAILABLE\""));
        assertFalse(build.body().contains("a".repeat(64)));
        assertTrue(build.headers().allValues("set-cookie").isEmpty());
        assertEquals(200, get(client, "/actuator/health", null).statusCode());
        assertEquals(200, get(client, "/api/admin/auth/csrf", null).statusCode());
    }

    private HttpResponse<String> get(HttpClient client, String path, String authorization)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
