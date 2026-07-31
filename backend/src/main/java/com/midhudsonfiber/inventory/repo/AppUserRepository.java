package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<AppUser> findAllByOrderByUsernameAsc();
}
