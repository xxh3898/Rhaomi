package kr.co.rhaomi.backend.config;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import kr.co.rhaomi.backend.auth.AdminUserDetailsService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableConfigurationProperties({BootstrapAdminProperties.class, AdminWebAuthnProperties.class})
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository)
            throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/auth/csrf", "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login")
                        .permitAll()
                        .requestMatchers(
                                "/api/admin/auth/me",
                                "/api/admin/auth/logout",
                                "/api/admin/auth/webauthn/registration/options",
                                "/api/admin/auth/webauthn/registration",
                                "/api/admin/auth/webauthn/authentication/options",
                                "/api/admin/auth/webauthn/authentication",
                                "/api/admin/auth/webauthn/status",
                                "/api/admin/auth/recovery-codes/verify",
                                "/api/admin/auth/recovery-codes/rotate")
                        .authenticated()
                        .requestMatchers(
                                "/api/admin/auth/webauthn/credentials",
                                "/api/admin/auth/webauthn/credentials/**")
                        .hasAuthority("ADMIN_SECOND_FACTOR_VERIFIED")
                        .requestMatchers("/api/admin/auth/**")
                        .denyAll()
                        .requestMatchers("/actuator/**")
                        .denyAll()
                        .requestMatchers("/api/admin/**")
                        .hasAuthority("ADMIN_SECOND_FACTOR_VERIFIED")
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(
            AdminUserDetailsService adminUserDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(adminUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> writeJson(
                response, 401, "UNAUTHORIZED", "인증이 필요합니다.");
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> writeJson(
                response, 403, "FORBIDDEN", "요청 권한 또는 CSRF token을 확인해 주세요.");
    }

    private static void writeJson(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
