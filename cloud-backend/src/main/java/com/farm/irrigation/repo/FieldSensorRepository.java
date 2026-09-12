package com.farm.irrigation.repo;

import com.farm.irrigation.domain.FieldSensor;
import com.farm.irrigation.domain.FieldSensorId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FieldSensorRepository extends JpaRepository<FieldSensor, FieldSensorId> {

    List<FieldSensor> findByIdFieldId(Long fieldId);

    @Query("select fs.id.deviceCode from FieldSensor fs where fs.id.fieldId = :fieldId")
    List<String> findDeviceCodesByFieldId(@Param("fieldId") Long fieldId);

    @Query("select fs.id.deviceCode from FieldSensor fs where fs.id.fieldId = :fieldId and fs.sensorRole = :role")
    List<String> findDeviceCodesByFieldIdAndRole(@Param("fieldId") Long fieldId, @Param("role") String role);

    @Query("select fs.id.fieldId from FieldSensor fs where fs.id.deviceCode = :deviceCode")
    List<Long> findFieldIdsByDeviceCode(@Param("deviceCode") String deviceCode);

    @Modifying
    @Query("delete from FieldSensor fs where fs.id.fieldId = :fieldId")
    int deleteByFieldId(@Param("fieldId") Long fieldId);
}
