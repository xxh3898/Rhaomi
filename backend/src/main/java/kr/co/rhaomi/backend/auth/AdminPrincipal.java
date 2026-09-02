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
    private final AdminAuthenticationStage authenticationStage;

    public AdminPrincipal(
            UUID id,
            String email,
            String passwordHash,
            AdminRole role,
            boolean active,
            AdminAuthenticationStage authenticationStage) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
        this.authenticationStage = authenticationStage;
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

    public AdminAuthenticationStage authenticationStage() {
        return authenticationStage;
    }

    public AdminPrincipal withAuthenticationStage(AdminAuthenticationStage stage) {
        return new AdminPrincipal(id, email, null, role, active, stage);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        var authorities = new java.util.ArrayList<GrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        authorities.add(new SimpleGrantedAuthority("ADMIN_FIRST_FACTOR_VERIFIED"));
        if (authenticationStage == AdminAuthenticationStage.SECOND_FACTOR_VERIFIED) {
            authorities.add(new SimpleGrantedAuthority("ADMIN_SECOND_FACTOR_VERIFIED"));
        }
        if (authenticationStage == AdminAuthenticationStage.RECOVERY_ROTATION_REQUIRED) {
            authorities.add(new SimpleGrantedAuthority("ADMIN_RECOVERY_ROTATION_REQUIRED"));
        }
        return List.copyOf(authorities);
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
        return "AdminPrincipal{id=" + id + ", role=" + role + ", stage=" + authenticationStage + "}";
    }
}
