package com.farm.irrigation.repo;

import com.farm.irrigation.domain.IrrigationJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IrrigationJobRepository extends JpaRepository<IrrigationJob, Long> {

    Page<IrrigationJob> findByFieldId(Long fieldId, Pageable pageable);

    Page<IrrigationJob> findByStatus(String status, Pageable pageable);

    Page<IrrigationJob> findByFieldIdAndStatus(Long fieldId, String status, Pageable pageable);

    List<IrrigationJob> findByStatusOrderByStartTimeDesc(String status);

    Optional<IrrigationJob> findFirstByFieldIdOrderByStartTimeDesc(Long fieldId);

    Optional<IrrigationJob> findFirstByFieldIdAndStatusOrderByStartTimeDesc(Long fieldId, String status);

    long countByFieldIdAndStatus(Long fieldId, String status);
}
