package com.farm.irrigation.repo;

import com.farm.irrigation.domain.IrrigationLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IrrigationLedgerRepository extends JpaRepository<IrrigationLedger, Long> {

    Page<IrrigationLedger> findByFieldIdOrderByStartTimeDesc(Long fieldId, Pageable pageable);

    Page<IrrigationLedger> findByKindOrderByStartTimeDesc(String kind, Pageable pageable);

    Page<IrrigationLedger> findByFieldIdAndKindOrderByStartTimeDesc(Long fieldId, String kind, Pageable pageable);

    Page<IrrigationLedger> findAllByOrderByStartTimeDesc(Pageable pageable);

    Optional<IrrigationLedger> findFirstByJobIdAndKindOrderByIdAsc(Long jobId, String kind);

    List<IrrigationLedger> findByJobId(Long jobId);

    @Query("select coalesce(sum(l.waterM3),0), coalesce(sum(l.fertilizerL),0), coalesce(sum(l.fertilizerKg),0), count(l) "
            + "from IrrigationLedger l where l.startTime >= :from and l.startTime < :to")
    Object[] summarize(@Param("from") Instant from, @Param("to") Instant to);
}
