package kr.co.rhaomi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rhaomi.bootstrap-admin")
public record BootstrapAdminProperties(boolean enabled, String email, String password) {}
