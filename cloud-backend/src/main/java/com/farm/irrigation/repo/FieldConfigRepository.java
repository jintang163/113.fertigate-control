package com.farm.irrigation.repo;

import com.farm.irrigation.domain.FieldConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldConfigRepository extends JpaRepository<FieldConfig, Long> {
}
