package kr.co.rhaomi.backend.build;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableConfigurationProperties(BuildServiceProperties.class)
public class BuildSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain buildSecurityFilterChain(
            HttpSecurity http, BuildServiceProperties properties)
            throws Exception {
        var authenticationFilter = new BuildServiceAuthenticationFilter(properties);
        http
                .securityMatcher("/api/build/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/build/snapshot",
                                "/api/build/media/*/content")
                        .hasRole("BUILD_SERVICE")
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                BuildSecurityResponses.unauthorized(response))
                        .accessDeniedHandler((request, response, exception) ->
                                BuildSecurityResponses.forbidden(response)))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository())
                        .requireExplicitSave(true))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(authenticationFilter, AnonymousAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
