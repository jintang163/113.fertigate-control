package com.farm.irrigation.repo;

import com.farm.irrigation.domain.DeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, String> {

    List<DeviceCommand> findByJobId(Long jobId);

    List<DeviceCommand> findByDeviceCodeOrderByCreatedAtDesc(String deviceCode);

    List<DeviceCommand> findByStatus(String status);
}
