package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.service.LedgerService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** 灌肥台账查询与用量统计。 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final ControlProperties props;

    public LedgerController(LedgerService ledgerService, ControlProperties props) {
        this.ledgerService = ledgerService;
        this.props = props;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) Long fieldId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<?> result = ledgerService.query(fieldId, kind, page, size);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", result.getContent().stream()
                .map(l -> ledgerService.toView((com.farm.irrigation.domain.IrrigationLedger) l)).toList());
        m.put("total", result.getTotalElements());
        m.put("page", result.getNumber());
        m.put("size", result.getSize());
        return ApiResponse.ok(m);
    }

    /** 今日/指定日用量汇总：水 m³、肥液 L、次数。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(required = false) String date) {
        ZoneId zone;
        try {
            zone = ZoneId.of(props.getZoneId());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        LocalDate day = (date == null || date.isBlank()) ? LocalDate.now(zone) : LocalDate.parse(date);
        return ApiResponse.ok(ledgerService.summarize(day, zone));
    }
}
