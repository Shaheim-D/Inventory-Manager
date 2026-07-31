package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.LifecycleTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LifecycleTransitionRepository extends JpaRepository<LifecycleTransition, Long> {
    List<LifecycleTransition> findByCategoryId(Long categoryId);
    List<LifecycleTransition> findByCategoryIdAndFromStateId(Long categoryId, Long fromStateId);
    boolean existsByCategoryIdAndFromStateIdAndToStateId(Long categoryId, Long fromStateId, Long toStateId);
}
