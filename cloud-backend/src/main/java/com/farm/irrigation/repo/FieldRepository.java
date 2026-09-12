package com.farm.irrigation.repo;

import com.farm.irrigation.domain.FieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldRepository extends JpaRepository<FieldEntity, Long> {
}
