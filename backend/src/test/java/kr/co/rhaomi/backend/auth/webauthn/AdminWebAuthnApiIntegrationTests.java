package kr.co.rhaomi.backend.auth.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rhaomi.admin-auth.webauthn.required=true",
            "rhaomi.admin-auth.webauthn.rp-id=localhost",
            "rhaomi.admin-auth.webauthn.origin=http://localhost:3000",
            "rhaomi.admin-auth.webauthn.rp-name=Rhaomi Test Admin",
            "rhaomi.admin-auth.webauthn.challenge-ttl=5m"
        })
@ActiveProfiles("test")
class AdminWebAuthnApiIntegrationTests {

    private static final String ADMIN_EMAIL = "webauthn.contract@example.com";
    private static final String OTHER_ADMIN_EMAIL = "webauthn.other@example.com";
    private static final String ADMIN_PASSWORD = "local-webauthn-password-123!";
    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "http://localhost:3000";
    private static final Instant NOW = Instant.parse("2035-01-01T00:00:00Z");
    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUsers;

    @Autowired
    private AdminWebAuthnCredentialRepository credentials;

    @Autowired
    private AdminRecoveryCodeRepository recoveryCodes;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(ZoneOffset.UTC)).thenReturn(clock);
        recoveryCodes.deleteAllInBatch();
        credentials.deleteAllInBatch();
        adminUsers.findByEmail(ADMIN_EMAIL).ifPresent(adminUsers::delete);
        adminUsers.findByEmail(OTHER_ADMIN_EMAIL).ifPresent(adminUsers::delete);
        adminUsers.flush();
        createAdmin(ADMIN_EMAIL);
    }

    @AfterEach
    void tearDown() {
        recoveryCodes.deleteAllInBatch();
        credentials.deleteAllInBatch();
        adminUsers.findByEmail(ADMIN_EMAIL).ifPresent(adminUsers::delete);
        adminUsers.findByEmail(OTHER_ADMIN_EMAIL).ifPresent(adminUsers::delete);
        adminUsers.flush();
    }

    @Test
    void should_requireSecondFactorAndCompleteActualRegistrationAndAssertion() throws Exception {
        var enrollmentClient = newClient();
        assertEquals(200, login(enrollmentClient, ADMIN_EMAIL).statusCode());
        assertEquals(403, get(enrollmentClient, "/api/admin/breeds").statusCode());

        var initialStatus = json(get(enrollmentClient, "/api/admin/auth/webauthn/status"));
        assertEquals("FIRST_FACTOR_VERIFIED", initialStatus.get("authenticationStage").asText());
        assertTrue(initialStatus.get("initialEnrollmentRequired").asBoolean());

        var optionsResponse = get(
                enrollmentClient, "/api/admin/auth/webauthn/registration/options");
        assertEquals(200, optionsResponse.statusCode());
        var firstFactorSessionId = sessionId(enrollmentClient);
        var options = json(optionsResponse);
        assertTrue(decode(options.get("challenge").asText()).length >= 32);
        assertEquals(
                "required",
                options.get("authenticatorSelection").get("userVerification").asText());
        var fixture = PasskeyFixture.create(
                options.get("user").get("id").asText(), RP_ID, ORIGIN);
        var registration = fixture.registration(options.get("challenge").asText());

        var registered = postJson(
                enrollmentClient,
                "/api/admin/auth/webauthn/registration",
                objectMapper.writeValueAsString(registration),
                fetchCsrf(enrollmentClient));

        assertEquals(200, registered.statusCode());
        assertEquals(
                "SECOND_FACTOR_VERIFIED",
                json(registered).get("authenticationStage").asText());
        assertEquals(1, credentials.count());
        assertNotEquals(firstFactorSessionId, sessionId(enrollmentClient));
        assertEquals(200, get(enrollmentClient, "/api/admin/breeds").statusCode());
        var credentialList = get(
                enrollmentClient, "/api/admin/auth/webauthn/credentials");
        assertEquals(200, credentialList.statusCode());
        var credentialMetadata = json(credentialList).get(0);
        assertEquals("테스트 Passkey", credentialMetadata.get("label").asText());
        assertFalse(credentialMetadata.has("credentialId"));
        assertFalse(credentialMetadata.has("publicKeyCose"));
        assertFalse(credentialMetadata.has("attestationObject"));

        var authenticationClient = newClient();
        assertEquals(200, login(authenticationClient, ADMIN_EMAIL).statusCode());
        assertEquals(403, get(authenticationClient, "/api/admin/breeds").statusCode());
        assertEquals(
                403,
                get(authenticationClient, "/api/admin/auth/webauthn/credentials")
                        .statusCode());
        assertEquals(
                403,
                get(authenticationClient, "/api/admin/auth/webauthn/registration/options")
                        .statusCode());
        var requestOptionsResponse = get(
                authenticationClient, "/api/admin/auth/webauthn/authentication/options");
        assertEquals(200, requestOptionsResponse.statusCode());
        var beforeSecondFactorSessionId = sessionId(authenticationClient);
        var requestOptions = json(requestOptionsResponse);
        assertEquals("required", requestOptions.get("userVerification").asText());
        var assertion = fixture.assertion(
                requestOptions.get("challenge").asText(), ORIGIN, (byte) 0x05, false);

        var authenticated = postJson(
                authenticationClient,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(assertion),
                fetchCsrf(authenticationClient));

        assertEquals(200, authenticated.statusCode());
        assertEquals(
                "SECOND_FACTOR_VERIFIED",
                json(authenticated).get("authenticationStage").asText());
        assertNotEquals(beforeSecondFactorSessionId, sessionId(authenticationClient));
        assertEquals(200, get(authenticationClient, "/api/admin/breeds").statusCode());
        assertEquals(
                200,
                get(authenticationClient, "/api/admin/auth/webauthn/registration/options")
                        .statusCode());
        assertTrue(credentials.findAll().getFirst().toCredentialRecord().getSignatureCount() >= 1);
    }

    @Test
    void should_rejectInvalidSignatureOriginMissingUvAndReplay_withoutLeakingDetails()
            throws Exception {
        var fixture = enrollPasskey();

        var invalidSignature = authenticate(
                fixture,
                ORIGIN,
                (byte) 0x05,
                true,
                false);
        var wrongOrigin = authenticate(
                fixture,
                "http://evil.example.test",
                (byte) 0x05,
                false,
                false);
        var wrongRp = authenticateWithRpId(fixture, "evil.example.test");
        var unknownCredential = authenticateUnknownCredential(fixture);
        var missingUv = authenticate(
                fixture,
                ORIGIN,
                (byte) 0x01,
                false,
                false);
        var replay = authenticate(fixture, ORIGIN, (byte) 0x05, false, true);

        for (var response :
                List.of(invalidSignature, wrongOrigin, wrongRp, unknownCredential, missingUv, replay)) {
            assertEquals(401, response.statusCode());
            assertTrue(response.body().contains("WEBAUTHN_VERIFICATION_FAILED"));
            assertFalse(response.body().toLowerCase().contains("signature"));
            assertFalse(response.body().toLowerCase().contains("origin"));
            assertFalse(response.body().toLowerCase().contains("challenge"));
        }
    }

    @Test
    void should_returnGenericVerificationFailure_whenRegistrationBodyIsNull() throws Exception {
        var client = newClient();
        assertEquals(200, login(client, ADMIN_EMAIL).statusCode());
        assertEquals(
                200,
                get(client, "/api/admin/auth/webauthn/registration/options").statusCode());

        var response = postJson(
                client,
                "/api/admin/auth/webauthn/registration",
                "null",
                fetchCsrf(client));

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("WEBAUTHN_VERIFICATION_FAILED"));
        assertFalse(response.body().toLowerCase().contains("null"));
        assertEquals(0, credentials.count());
    }

    @Test
    void should_bindChallengeToAccountPurposeAndExpiry() throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/registration/options"));
        var fixture = PasskeyFixture.create(
                options.get("user").get("id").asText(), RP_ID, ORIGIN);
        var registration = fixture.registration(options.get("challenge").asText());

        createAdmin(OTHER_ADMIN_EMAIL);
        login(client, OTHER_ADMIN_EMAIL);
        var wrongAccount = postJson(
                client,
                "/api/admin/auth/webauthn/registration",
                objectMapper.writeValueAsString(registration),
                fetchCsrf(client));
        assertEquals(401, wrongAccount.statusCode());
        assertEquals(0, credentials.count());

        var expiryClient = newClient();
        login(expiryClient, ADMIN_EMAIL);
        var expiryOptions = json(get(
                expiryClient, "/api/admin/auth/webauthn/registration/options"));
        var expiryFixture = PasskeyFixture.create(
                expiryOptions.get("user").get("id").asText(), RP_ID, ORIGIN);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(5)));
        var expired = postJson(
                expiryClient,
                "/api/admin/auth/webauthn/registration",
                objectMapper.writeValueAsString(
                        expiryFixture.registration(expiryOptions.get("challenge").asText())),
                fetchCsrf(expiryClient));
        assertEquals(401, expired.statusCode());
        assertEquals(0, credentials.count());

        when(clock.instant()).thenReturn(NOW);
        var purposeClient = newClient();
        login(purposeClient, ADMIN_EMAIL);
        var registrationOptions = json(get(
                purposeClient, "/api/admin/auth/webauthn/registration/options"));
        var purposeFixture = PasskeyFixture.create(
                registrationOptions.get("user").get("id").asText(), RP_ID, ORIGIN);
        var wrongPurpose = postJson(
                purposeClient,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(purposeFixture.assertion(
                        registrationOptions.get("challenge").asText(),
                        ORIGIN,
                        (byte) 0x05,
                        false)),
                fetchCsrf(purposeClient));
        assertEquals(401, wrongPurpose.statusCode());
    }

    @Test
    void should_issueHashOnlyRecoveryCodesInvalidateSetAndProtectFinalFactor()
            throws Exception {
        var fixture = enrollPasskey();
        var secondFactorClient = authenticateSuccessfully(fixture);
        var onlyCredential = credentials.findAll().getFirst().id();

        var blockedRemoval = delete(
                secondFactorClient,
                "/api/admin/auth/webauthn/credentials/" + onlyCredential,
                fetchCsrf(secondFactorClient));
        assertEquals(403, blockedRemoval.statusCode());

        var rotation = postJson(
                secondFactorClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(secondFactorClient));
        assertEquals(200, rotation.statusCode());
        var plaintext = json(rotation).get("recoveryCodes").get(0).asText();
        assertEquals(10, recoveryCodes.count());
        assertTrue(recoveryCodes.findAll().stream()
                .allMatch(code -> code.codeHash().matches("[0-9a-f]{64}")));
        assertTrue(recoveryCodes.findAll().stream()
                .noneMatch(code -> code.codeHash().equals(plaintext)));

        var allowedRemoval = delete(
                secondFactorClient,
                "/api/admin/auth/webauthn/credentials/" + onlyCredential,
                fetchCsrf(secondFactorClient));
        assertEquals(204, allowedRemoval.statusCode());
        assertEquals(
                0,
                credentials.countByAdminUserIdAndStatus(
                        adminUsers.findByEmail(ADMIN_EMAIL).orElseThrow().getId(),
                        AdminWebAuthnCredentialStatus.ACTIVE));

        var recoveryClient = newClient();
        login(recoveryClient, ADMIN_EMAIL);
        var recovered = postJson(
                recoveryClient,
                "/api/admin/auth/recovery-codes/verify",
                objectMapper.writeValueAsString(new RecoveryCodeRequest(plaintext)),
                fetchCsrf(recoveryClient));
        assertEquals(200, recovered.statusCode());
        assertEquals(
                "RECOVERY_ROTATION_REQUIRED",
                json(recovered).get("authenticationStage").asText());
        assertEquals(403, get(recoveryClient, "/api/admin/breeds").statusCode());
        assertEquals(
                403,
                get(recoveryClient, "/api/admin/auth/webauthn/registration/options")
                        .statusCode());
        assertEquals(
                403,
                get(recoveryClient, "/api/admin/auth/webauthn/authentication/options")
                        .statusCode());

        var replayClient = newClient();
        login(replayClient, ADMIN_EMAIL);
        var replay = postJson(
                replayClient,
                "/api/admin/auth/recovery-codes/verify",
                objectMapper.writeValueAsString(new RecoveryCodeRequest(plaintext)),
                fetchCsrf(replayClient));
        assertEquals(401, replay.statusCode());

        var replacement = postJson(
                recoveryClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(recoveryClient));
        assertEquals(200, replacement.statusCode());
        assertEquals(200, get(recoveryClient, "/api/admin/breeds").statusCode());
        assertNotEquals(
                plaintext,
                json(replacement).get("recoveryCodes").get(0).asText());
    }

    @Test
    void should_requireRecoveryCodeRotationBeforeAnotherPasskeyAssertionCanPromote()
            throws Exception {
        var fixture = enrollPasskey();
        var verifiedClient = authenticateSuccessfully(fixture);
        var rotation = postJson(
                verifiedClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(verifiedClient));
        assertEquals(200, rotation.statusCode());
        var recoveryCode = json(rotation).get("recoveryCodes").get(0).asText();

        var recoveryClient = newClient();
        login(recoveryClient, ADMIN_EMAIL);
        var recovered = postJson(
                recoveryClient,
                "/api/admin/auth/recovery-codes/verify",
                objectMapper.writeValueAsString(new RecoveryCodeRequest(recoveryCode)),
                fetchCsrf(recoveryClient));

        assertEquals(200, recovered.statusCode());
        assertEquals(
                "RECOVERY_ROTATION_REQUIRED",
                json(recovered).get("authenticationStage").asText());
        assertEquals(
                403,
                get(recoveryClient, "/api/admin/auth/webauthn/authentication/options")
                        .statusCode());
        assertEquals(
                403,
                get(recoveryClient, "/api/admin/auth/webauthn/registration/options")
                        .statusCode());
        assertEquals(403, get(recoveryClient, "/api/admin/breeds").statusCode());

        var replacement = postJson(
                recoveryClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(recoveryClient));
        assertEquals(200, replacement.statusCode());
        assertEquals(200, get(recoveryClient, "/api/admin/breeds").statusCode());
    }

    @Test
    void should_rejectStateChangingMfaRequestsWithoutCsrfAndInvalidateLogoutSession()
            throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var registrationOptions = json(get(
                client, "/api/admin/auth/webauthn/registration/options"));
        var fixture = PasskeyFixture.create(
                registrationOptions.get("user").get("id").asText(), RP_ID, ORIGIN);

        assertEquals(
                403,
                postJsonWithoutCsrf(
                                client,
                                "/api/admin/auth/webauthn/registration",
                                objectMapper.writeValueAsString(fixture.registration(
                                        registrationOptions.get("challenge").asText())))
                        .statusCode());
        assertEquals(0, credentials.count());

        var logout = postJson(
                client, "/api/admin/auth/logout", "{}", fetchCsrf(client));
        assertEquals(204, logout.statusCode());
        assertEquals(
                401,
                get(client, "/api/admin/auth/webauthn/status").statusCode());

        var enrolledFixture = enrollPasskey();
        var verifiedClient = authenticateSuccessfully(enrolledFixture);
        var rotation = postJson(
                verifiedClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(verifiedClient));
        assertEquals(200, rotation.statusCode());
        var recoveryCode = json(rotation).get("recoveryCodes").get(0).asText();
        var credentialId = credentials.findAll().getFirst().id();

        assertEquals(
                403,
                postJsonWithoutCsrf(
                                verifiedClient,
                                "/api/admin/auth/recovery-codes/rotate",
                                "{}")
                        .statusCode());
        assertEquals(
                403,
                deleteWithoutCsrf(
                                verifiedClient,
                                "/api/admin/auth/webauthn/credentials/" + credentialId)
                        .statusCode());

        var authenticationClient = newClient();
        login(authenticationClient, ADMIN_EMAIL);
        var authenticationOptions = json(get(
                authenticationClient, "/api/admin/auth/webauthn/authentication/options"));
        assertEquals(
                403,
                postJsonWithoutCsrf(
                                authenticationClient,
                                "/api/admin/auth/webauthn/authentication",
                                objectMapper.writeValueAsString(enrolledFixture.assertion(
                                        authenticationOptions.get("challenge").asText(),
                                        ORIGIN,
                                        (byte) 0x05,
                                        false)))
                        .statusCode());

        var recoveryClient = newClient();
        login(recoveryClient, ADMIN_EMAIL);
        assertEquals(
                403,
                postJsonWithoutCsrf(
                                recoveryClient,
                                "/api/admin/auth/recovery-codes/verify",
                                objectMapper.writeValueAsString(
                                        new RecoveryCodeRequest(recoveryCode)))
                        .statusCode());
    }

    @Test
    void should_rejectRevokedCredentialAssertionWithGenericFailure() throws Exception {
        var fixture = enrollPasskey();
        var verifiedClient = authenticateSuccessfully(fixture);
        postJson(
                verifiedClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(verifiedClient));

        var pendingClient = newClient();
        login(pendingClient, ADMIN_EMAIL);
        var options = json(get(
                pendingClient, "/api/admin/auth/webauthn/authentication/options"));

        var credentialId = credentials.findAll().getFirst().id();
        assertEquals(
                204,
                delete(
                                verifiedClient,
                                "/api/admin/auth/webauthn/credentials/" + credentialId,
                                fetchCsrf(verifiedClient))
                        .statusCode());

        var rejected = postJson(
                pendingClient,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(fixture.assertion(
                        options.get("challenge").asText(), ORIGIN, (byte) 0x05, false)),
                fetchCsrf(pendingClient));

        assertEquals(401, rejected.statusCode());
        assertTrue(rejected.body().contains("WEBAUTHN_VERIFICATION_FAILED"));
        assertFalse(rejected.body().toLowerCase().contains("revoked"));
        assertFalse(rejected.body().toLowerCase().contains("credential"));
    }

    @Test
    void should_notPromoteInactiveAdminThroughPasskeyOrRecovery() throws Exception {
        var fixture = enrollPasskey();
        var verifiedClient = authenticateSuccessfully(fixture);
        var rotation = postJson(
                verifiedClient,
                "/api/admin/auth/recovery-codes/rotate",
                "{}",
                fetchCsrf(verifiedClient));
        assertEquals(200, rotation.statusCode());
        var recoveryCode = json(rotation).get("recoveryCodes").get(0).asText();

        var assertionClient = newClient();
        login(assertionClient, ADMIN_EMAIL);
        var options = json(get(
                assertionClient, "/api/admin/auth/webauthn/authentication/options"));
        var recoveryClient = newClient();
        login(recoveryClient, ADMIN_EMAIL);

        var admin = adminUsers.findByEmail(ADMIN_EMAIL).orElseThrow();
        admin.deactivate();
        adminUsers.saveAndFlush(admin);

        var assertion = postJson(
                assertionClient,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(fixture.assertion(
                        options.get("challenge").asText(), ORIGIN, (byte) 0x05, false)),
                fetchCsrf(assertionClient));
        var recovery = postJson(
                recoveryClient,
                "/api/admin/auth/recovery-codes/verify",
                objectMapper.writeValueAsString(new RecoveryCodeRequest(recoveryCode)),
                fetchCsrf(recoveryClient));

        assertEquals(403, assertion.statusCode());
        assertEquals(403, recovery.statusCode());
        assertEquals(403, get(assertionClient, "/api/admin/breeds").statusCode());
        assertEquals(403, get(recoveryClient, "/api/admin/breeds").statusCode());
    }

    private PasskeyFixture enrollPasskey() throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/registration/options"));
        var fixture = PasskeyFixture.create(
                options.get("user").get("id").asText(), RP_ID, ORIGIN);
        var response = postJson(
                client,
                "/api/admin/auth/webauthn/registration",
                objectMapper.writeValueAsString(
                        fixture.registration(options.get("challenge").asText())),
                fetchCsrf(client));
        assertEquals(200, response.statusCode());
        return fixture;
    }

    private HttpResponse<String> authenticate(
            PasskeyFixture fixture,
            String origin,
            byte flags,
            boolean corruptSignature,
            boolean replay)
            throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/authentication/options"));
        var assertion = fixture.assertion(options.get("challenge").asText(), origin, flags, corruptSignature);
        var csrf = fetchCsrf(client);
        var first = postJson(
                client,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(assertion),
                csrf);
        if (!replay) {
            return first;
        }
        assertEquals(200, first.statusCode());
        return postJson(
                client,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(assertion),
                fetchCsrf(client));
    }

    private TestClient authenticateSuccessfully(PasskeyFixture fixture) throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/authentication/options"));
        var response = postJson(
                client,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(fixture.assertion(
                        options.get("challenge").asText(), ORIGIN, (byte) 0x05, false)),
                fetchCsrf(client));
        assertEquals(200, response.statusCode());
        return client;
    }

    private HttpResponse<String> authenticateWithRpId(PasskeyFixture fixture, String rpId)
            throws Exception {
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/authentication/options"));
        return postJson(
                client,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(fixture.assertionForRpId(
                        options.get("challenge").asText(),
                        ORIGIN,
                        rpId,
                        (byte) 0x05,
                        false)),
                fetchCsrf(client));
    }

    private HttpResponse<String> authenticateUnknownCredential(PasskeyFixture fixture)
            throws Exception {
        var unknown = PasskeyFixture.create(fixture.userHandle, RP_ID, ORIGIN);
        var client = newClient();
        login(client, ADMIN_EMAIL);
        var options = json(get(client, "/api/admin/auth/webauthn/authentication/options"));
        return postJson(
                client,
                "/api/admin/auth/webauthn/authentication",
                objectMapper.writeValueAsString(unknown.assertion(
                        options.get("challenge").asText(), ORIGIN, (byte) 0x05, false)),
                fetchCsrf(client));
    }

    private AdminUser createAdmin(String email) {
        return adminUsers.saveAndFlush(
                AdminUser.create(email, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    private HttpResponse<String> login(TestClient client, String email) throws Exception {
        return postJson(
                client,
                "/api/admin/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                fetchCsrf(client));
    }

    private String fetchCsrf(TestClient client) throws Exception {
        var response = get(client, "/api/admin/auth/csrf");
        assertEquals(200, response.statusCode());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private HttpResponse<String> get(TestClient client, String path) throws Exception {
        return client.httpClient().send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(
            TestClient client, String path, String body, String csrf) throws Exception {
        return client.httpClient().send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJsonWithoutCsrf(
            TestClient client, String path, String body) throws Exception {
        return client.httpClient().send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(
            TestClient client, String path, String csrf) throws Exception {
        return client.httpClient().send(
                HttpRequest.newBuilder(uri(path))
                        .header("X-CSRF-TOKEN", csrf)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> deleteWithoutCsrf(TestClient client, String path)
            throws Exception {
        return client.httpClient().send(
                HttpRequest.newBuilder(uri(path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private TestClient newClient() {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new TestClient(
                HttpClient.newBuilder()
                        .cookieHandler(cookieManager)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                cookieManager);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static String sessionId(TestClient client) {
        return client.cookieManager().getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private static final class PasskeyFixture {

        private final KeyPair keyPair;
        private final byte[] credentialId;
        private final String userHandle;
        private final String rpId;

        private PasskeyFixture(
                KeyPair keyPair, byte[] credentialId, String userHandle, String rpId) {
            this.keyPair = keyPair;
            this.credentialId = credentialId;
            this.userHandle = userHandle;
            this.rpId = rpId;
        }

        static PasskeyFixture create(String userHandle, String rpId, String origin)
                throws Exception {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var credentialId = new byte[32];
            new SecureRandom().nextBytes(credentialId);
            return new PasskeyFixture(generator.generateKeyPair(), credentialId, userHandle, rpId);
        }

        WebAuthnRegistrationRequest registration(String challenge) throws Exception {
            var clientData = clientData("webauthn.create", challenge, ORIGIN);
            var authData = registrationAuthenticatorData();
            var id = BASE64URL.encodeToString(credentialId);
            return new WebAuthnRegistrationRequest(
                    id,
                    id,
                    "public-key",
                    "platform",
                    new WebAuthnRegistrationRequest.ClientExtensionResults(null),
                    new WebAuthnRegistrationRequest.RegistrationResponse(
                            BASE64URL.encodeToString(clientData),
                            BASE64URL.encodeToString(attestationObject(authData)),
                            List.of("internal")),
                    "테스트 Passkey");
        }

        WebAuthnAuthenticationRequest assertion(
                String challenge, String origin, byte flags, boolean corruptSignature)
                throws Exception {
            return assertionForRpId(challenge, origin, rpId, flags, corruptSignature);
        }

        WebAuthnAuthenticationRequest assertionForRpId(
                String challenge,
                String origin,
                String authenticatorRpId,
                byte flags,
                boolean corruptSignature)
                throws Exception {
            var clientData = clientData("webauthn.get", challenge, origin);
            var authenticatorData = assertionAuthenticatorData(authenticatorRpId, flags);
            var signed = ByteBuffer.allocate(authenticatorData.length + 32)
                    .put(authenticatorData)
                    .put(sha256(clientData))
                    .array();
            var signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(signed);
            var signature = signer.sign();
            if (corruptSignature) {
                signature[signature.length - 1] ^= 1;
            }
            var id = BASE64URL.encodeToString(credentialId);
            return new WebAuthnAuthenticationRequest(
                    id,
                    id,
                    "public-key",
                    "platform",
                    new WebAuthnRegistrationRequest.ClientExtensionResults(null),
                    new WebAuthnAuthenticationRequest.AssertionResponse(
                            BASE64URL.encodeToString(clientData),
                            BASE64URL.encodeToString(authenticatorData),
                            BASE64URL.encodeToString(signature),
                            userHandle));
        }

        private byte[] registrationAuthenticatorData() throws Exception {
            var publicKey = (ECPublicKey) keyPair.getPublic();
            var coseKey = new ByteArrayOutputStream();
            coseKey.write(0xa5);
            coseKey.write(new byte[] {0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21});
            writeByteString(coseKey, unsigned32(publicKey.getW().getAffineX()));
            coseKey.write(0x22);
            writeByteString(coseKey, unsigned32(publicKey.getW().getAffineY()));
            return ByteBuffer.allocate(32 + 1 + 4 + 16 + 2 + credentialId.length + coseKey.size())
                    .put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                    .put((byte) 0x45)
                    .putInt(0)
                    .put(new byte[16])
                    .putShort((short) credentialId.length)
                    .put(credentialId)
                    .put(coseKey.toByteArray())
                    .array();
        }

        private byte[] assertionAuthenticatorData(String authenticatorRpId, byte flags)
                throws Exception {
            return ByteBuffer.allocate(37)
                    .put(sha256(authenticatorRpId.getBytes(StandardCharsets.UTF_8)))
                    .put(flags)
                    .putInt(1)
                    .array();
        }

        private static byte[] attestationObject(byte[] authData) {
            var output = new ByteArrayOutputStream();
            output.write(0xa3);
            writeText(output, "fmt");
            writeText(output, "none");
            writeText(output, "attStmt");
            output.write(0xa0);
            writeText(output, "authData");
            writeByteString(output, authData);
            return output.toByteArray();
        }

        private static byte[] clientData(String type, String challenge, String origin) {
            return ("{\"type\":\""
                            + type
                            + "\",\"challenge\":\""
                            + challenge
                            + "\",\"origin\":\""
                            + origin
                            + "\",\"crossOrigin\":false}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        private static void writeText(ByteArrayOutputStream output, String value) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            output.write(0x60 + bytes.length);
            output.writeBytes(bytes);
        }

        private static void writeByteString(ByteArrayOutputStream output, byte[] value) {
            if (value.length < 24) {
                output.write(0x40 + value.length);
            } else if (value.length < 256) {
                output.write(0x58);
                output.write(value.length);
            } else {
                output.write(0x59);
                output.write(value.length >>> 8);
                output.write(value.length & 0xff);
            }
            output.writeBytes(value);
        }

        private static byte[] unsigned32(BigInteger value) {
            var bytes = value.toByteArray();
            if (bytes.length == 32) {
                return bytes;
            }
            if (bytes.length == 33 && bytes[0] == 0) {
                return Arrays.copyOfRange(bytes, 1, bytes.length);
            }
            var result = new byte[32];
            System.arraycopy(bytes, 0, result, result.length - bytes.length, bytes.length);
            return result;
        }

        private static byte[] sha256(byte[] value) throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
    }
}
