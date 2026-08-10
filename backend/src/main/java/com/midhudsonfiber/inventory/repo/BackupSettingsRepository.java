package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.BackupSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupSettingsRepository extends JpaRepository<BackupSettings, Short> {
}
