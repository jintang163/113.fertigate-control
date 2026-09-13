package com.farm.irrigation.control;

import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.FieldEntity;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 灌区灌溉时间窗判定（field_t.window_start_min/end_min/days）。
 * window_days 为周一..周日 7 位允许位图；时窗支持跨午夜。
 */
@Component
public class IrrigationWindow {

    private final ControlProperties props;

    public IrrigationWindow(ControlProperties props) {
        this.props = props;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(props.getZoneId());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** 暴露时窗时区（轮灌排程使用）。 */
    public ZoneId zoneId() {
        return zone();
    }

    public boolean isAllowedNow(FieldEntity field) {
        return isAllowed(field, Instant.now());
    }

    public boolean isAllowed(FieldEntity field, Instant at) {
        if (field.getWindowStartMin() == null || field.getWindowEndMin() == null) {
            return true; // 未配置时窗 = 全天允许
        }
        ZonedDateTime z = at.atZone(zone());
        String days = field.getWindowDays();
        if (days != null && days.length() == 7) {
            DayOfWeek dow = z.getDayOfWeek();
            int idx = dow.getValue() - 1; // Monday=0
            if (days.charAt(idx) != '1') {
                return false;
            }
        }
        int minuteOfDay = z.getHour() * 60 + z.getMinute();
        int start = field.getWindowStartMin();
        int end = field.getWindowEndMin();
        if (start == end) {
            return true;
        }
        if (start < end) {
            return minuteOfDay >= start && minuteOfDay < end;
        }
        // 跨午夜，如 22:00-06:00
        return minuteOfDay >= start || minuteOfDay < end;
    }

    /** 距离下一个允许时刻的秒数；已在窗内返回 0。 */
    public long secondsUntilAllowed(FieldEntity field) {
        return secondsUntilAllowed(field, Instant.now());
    }

    /** 下一个允许开灌时刻；已在窗内返回 now。 */
    public Instant nextAllowedAt(FieldEntity field) {
        long sec = secondsUntilAllowed(field, Instant.now());
        return Instant.now().plusSeconds(sec);
    }

    long secondsUntilAllowed(FieldEntity field, Instant at) {
        if (isAllowed(field, at)) {
            return 0;
        }
        // 简单步进搜索（每分钟一格，最多 7 天）
        ZoneId zid = zone();
        ZonedDateTime cursor = at.atZone(zid).withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 7 * 24 * 60; i++) {
            Instant candidate = cursor.toInstant();
            if (isAllowed(field, candidate)) {
                return Math.max(60, java.time.Duration.between(at, candidate).getSeconds());
            }
            cursor = cursor.plusMinutes(1);
        }
        return 24 * 3600L;
    }
}
