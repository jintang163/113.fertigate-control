package com.farm.irrigation.repo;

import com.farm.irrigation.domain.SensorLatest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SensorLatestRepository extends JpaRepository<SensorLatest, String> {

    List<SensorLatest> findByFieldId(Long fieldId);
}
