package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Branding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandingRepository extends JpaRepository<Branding, Short> {}
