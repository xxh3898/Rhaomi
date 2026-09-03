package kr.co.rhaomi.backend.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerErrorContractTests {

    private AuthenticationManager authenticationManager;
    private SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private SecurityContextRepository securityContextRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        sessionAuthenticationStrategy = mock(SessionAuthenticationStrategy.class);
        securityContextRepository = mock(SecurityContextRepository.class);
        var controller = new AuthController(
                authenticationManager,
                sessionAuthenticationStrategy,
                securityContextRepository,
                new AdminLoginRateLimiter());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void should_returnSame401_when_credentialsAreInvalid() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("credential detail"));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(content().string(not(containsString("credential detail"))));
    }

    @Test
    void should_return503WithoutDetails_when_authenticationServiceFails() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new AuthenticationServiceException("authentication service detail"));

        assertServiceUnavailable("authentication service detail");
    }

    @Test
    void should_return503WithoutDetails_when_repositoryAuthenticationFails() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new InternalAuthenticationServiceException("repository connection detail"));

        assertServiceUnavailable("repository connection detail");
    }

    @Test
    void should_return429WithRetryAfterWithoutAuthenticationOrSessionMutation_when_limitIsExceeded()
            throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("credential detail"));

        for (var index = 0; index < 5; index++) {
            performLogin().andExpect(status().isUnauthorized());
        }

        performLogin()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.message")
                        .value("로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(content().string(not(containsString("admin.contract@example.com"))))
                .andExpect(content().string(not(containsString("credential detail"))));

        verify(authenticationManager, times(5)).authenticate(any(Authentication.class));
        verifyNoInteractions(sessionAuthenticationStrategy, securityContextRepository);
    }

    @Test
    void should_restoreIdentifierQuota_when_authenticationServiceRepeatedlyFails() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new AuthenticationServiceException("authentication service detail"));

        for (var index = 0; index < 6; index++) {
            performLogin().andExpect(status().isServiceUnavailable());
        }

        verify(authenticationManager, times(6)).authenticate(any(Authentication.class));
    }

    private void assertServiceUnavailable(String internalDetail) throws Exception {
        performLogin()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("INVALID_CREDENTIALS"))))
                .andExpect(content().string(not(containsString(internalDetail))));
    }

    private org.springframework.test.web.servlet.ResultActions performLogin() throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody()));
    }

    private String loginBody() {
        return "{\"email\":\"admin.contract@example.com\",\"password\":\"local-test-password-123!\"}";
    }
}
