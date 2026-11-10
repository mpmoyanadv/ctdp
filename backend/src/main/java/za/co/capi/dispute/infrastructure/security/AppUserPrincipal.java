package za.co.capi.dispute.infrastructure.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.UserDomain;
import za.co.capi.dispute.domain.model.UserRole;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class AppUserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final UserRole role;
    private final UserDomain userDomain;
    private final String partnerInstitution;
    private final boolean locked;
    private final int tokenVersion;

    public AppUserPrincipal(AppUser user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.fullName = user.getFullName();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.userDomain = user.getUserDomain();
        this.partnerInstitution = user.getPartnerInstitution();
        this.locked = user.isLocked();
        this.tokenVersion = user.getTokenVersion();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserDomain getUserDomain() {
        return userDomain;
    }

    public String getPartnerInstitution() {
        return partnerInstitution;
    }

    public int getTokenVersion() {
        return tokenVersion;
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
    public boolean isAccountNonLocked() {
        return !locked;
    }
}

