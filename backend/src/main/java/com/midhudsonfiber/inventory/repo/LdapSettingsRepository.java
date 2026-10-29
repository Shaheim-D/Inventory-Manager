package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LdapSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LdapSettingsRepository extends JpaRepository<LdapSettings, Short> {
}
