package kr.co.rhaomi.backend.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminRole;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AdminPrincipal implements UserDetails, CredentialsContainer {

    private final UUID id;
    private final String email;
    private String passwordHash;
    private final AdminRole role;
    private final boolean active;

    public AdminPrincipal(
            UUID id, String email, String passwordHash, AdminRole role, boolean active) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public AdminRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "AdminPrincipal{id=" + id + ", role=" + role + "}";
    }
}
