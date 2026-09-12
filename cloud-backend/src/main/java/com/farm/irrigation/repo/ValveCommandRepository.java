package com.farm.irrigation.repo;

import com.farm.irrigation.domain.ValveCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ValveCommandRepository extends JpaRepository<ValveCommand, String> {

    Optional<ValveCommand> findFirstByJobIdAndCommandOrderByCreatedAtDesc(Long jobId, String command);

    List<ValveCommand> findByStatus(String status);
}
