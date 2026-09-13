package com.farm.irrigation.repo;

import com.farm.irrigation.domain.RotationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RotationPlanRepository extends JpaRepository<RotationPlan, Long> {

    List<RotationPlan> findByPlanDateOrderByIdDesc(LocalDate planDate);

    List<RotationPlan> findByStatusOrderByPlanDateDescIdDesc(String status);

    List<RotationPlan> findAllByOrderByIdDesc();
}
