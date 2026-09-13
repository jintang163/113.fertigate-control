package com.farm.irrigation.repo;

import com.farm.irrigation.domain.RotationPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RotationPlanItemRepository extends JpaRepository<RotationPlanItem, Long> {

    List<RotationPlanItem> findByPlanIdOrderBySeqAsc(Long planId);

    List<RotationPlanItem> findByStatusOrderByScheduledStartAsc(String status);

    /** Due items: PENDING whose scheduled time has arrived. */
    List<RotationPlanItem> findByStatusAndScheduledStartLessThanEqualOrderByPriorityAscScheduledStartAsc(
            String status, Instant now);

    List<RotationPlanItem> findByFieldIdAndStatus(Long fieldId, String status);
}
