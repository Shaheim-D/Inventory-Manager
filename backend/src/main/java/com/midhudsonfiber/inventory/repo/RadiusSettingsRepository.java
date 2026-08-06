package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.RadiusSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RadiusSettingsRepository extends JpaRepository<RadiusSettings, Short> {

    /** The single row. V26 seeds it, so this is never empty in a migrated database. */
    default RadiusSettings current() {
        return findById((short) 1).orElseGet(RadiusSettings::new);
    }
}
