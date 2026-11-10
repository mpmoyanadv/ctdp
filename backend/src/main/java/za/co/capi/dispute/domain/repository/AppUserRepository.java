package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.UserRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    Optional<AppUser> findFirstByPhoneNumber(String phoneNumber);
    List<AppUser> findByRoleInOrderByEmailAsc(List<UserRole> roles);
}

