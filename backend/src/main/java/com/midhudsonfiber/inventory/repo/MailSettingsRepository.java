package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.MailSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailSettingsRepository extends JpaRepository<MailSettings, Short> {
}
