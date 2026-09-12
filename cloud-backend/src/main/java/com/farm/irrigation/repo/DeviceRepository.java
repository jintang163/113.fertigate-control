package com.farm.irrigation.repo;

import com.farm.irrigation.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByCode(String code);

    List<Device> findByGatewaySn(String gatewaySn);

    List<Device> findByType(String type);

    List<Device> findByGatewaySnAndType(String gatewaySn, String type);

    boolean existsByCode(String code);
}
