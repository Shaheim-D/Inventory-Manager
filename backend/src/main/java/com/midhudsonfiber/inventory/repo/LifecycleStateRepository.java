package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LifecycleState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LifecycleStateRepository extends JpaRepository<LifecycleState, Long> {
    Optional<LifecycleState> findByName(String name);
    List<LifecycleState> findAllByOrderByIdAsc();
}
