package com.farm.irrigation.repo;

import com.farm.irrigation.domain.CropStageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CropStageRecordRepository extends JpaRepository<CropStageRecord, Long> {

    List<CropStageRecord> findByFieldIdOrderByRecordDateDescIdDesc(Long fieldId);

    Optional<CropStageRecord> findFirstByFieldIdOrderByRecordDateDescIdDesc(Long fieldId);
}
