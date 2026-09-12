package com.farm.irrigation.repo;

import com.farm.irrigation.domain.CropModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CropModelRepository extends JpaRepository<CropModel, String> {
}
