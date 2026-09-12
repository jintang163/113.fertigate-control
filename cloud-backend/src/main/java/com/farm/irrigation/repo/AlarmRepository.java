package com.farm.irrigation.repo;

import com.farm.irrigation.domain.Alarm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    Page<Alarm> findByLevel(String level, Pageable pageable);

    Page<Alarm> findByAcknowledged(boolean acknowledged, Pageable pageable);

    Page<Alarm> findByLevelAndAcknowledged(String level, boolean acknowledged, Pageable pageable);

    Page<Alarm> findByFieldId(Long fieldId, Pageable pageable);

    long countByLevelAndAcknowledged(String level, boolean acknowledged);

    boolean existsByFieldIdAndLevelAndAcknowledged(Long fieldId, String level, boolean acknowledged);
}
