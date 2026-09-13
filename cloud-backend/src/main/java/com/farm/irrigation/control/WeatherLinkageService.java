package com.farm.irrigation.control;

import com.farm.irrigation.domain.FieldConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 阈值策略 - 气象联动条件评估（control-logic.md §4 安全前置的一部分）。
 *
 * 仅当 {@code field_config.weather_linked=true} 时生效，校验：
 * 风速上限、气温上下限、空气湿度下限、当日降雨、未来 N 日预报降雨。
 * 任一不满足返回拦截原因；全部满足返回空列表。
 */
@Service
public class WeatherLinkageService {

    /**
     * @return 拦截原因列表（空表示允许）
     */
    public List<String> evaluate(FieldConfig cfg, FieldSnapshot snap) {
        List<String> failures = new ArrayList<>();
        if (cfg == null || !cfg.isWeatherLinked()) {
            return failures;
        }

        if (cfg.getWindMaxMs() != null && snap.getWindSpeed() != null
                && snap.getWindSpeed() > cfg.getWindMaxMs().doubleValue()) {
            failures.add("风速 " + round(snap.getWindSpeed()) + "m/s > 上限 "
                    + cfg.getWindMaxMs() + "m/s（气象联动禁灌）");
        }
        if (cfg.getTempMin() != null && snap.getAirTemp() != null
                && snap.getAirTemp() < cfg.getTempMin().doubleValue()) {
            failures.add("气温 " + round(snap.getAirTemp()) + "℃ < 下限 " + cfg.getTempMin() + "℃");
        }
        if (cfg.getTempMax() != null && snap.getAirTemp() != null
                && snap.getAirTemp() > cfg.getTempMax().doubleValue()) {
            failures.add("气温 " + round(snap.getAirTemp()) + "℃ > 上限 " + cfg.getTempMax() + "℃");
        }
        if (cfg.getHumidityMin() != null && snap.getAirHumidity() != null
                && snap.getAirHumidity() < cfg.getHumidityMin().doubleValue()) {
            failures.add("空气湿度 " + round(snap.getAirHumidity()) + "% < 下限 "
                    + cfg.getHumidityMin() + "%");
        }
        if (cfg.getRainTodaySkipMm() != null && snap.getRainfallToday() > 0
                && snap.getRainfallToday() >= cfg.getRainTodaySkipMm().doubleValue()) {
            failures.add("当日累计降雨 " + round(snap.getRainfallToday()) + "mm ≥ "
                    + cfg.getRainTodaySkipMm() + "mm，雨养跳过");
        }
        if (cfg.getForecastSkipMm() != null && snap.getRainForecast() != null) {
            int n = Math.max(1, cfg.getForecastDays());
            double maxDay = 0;
            double sum = 0;
            int counted = 0;
            for (Double mm : snap.getRainForecast()) {
                if (mm == null || counted >= n) {
                    continue;
                }
                maxDay = Math.max(maxDay, mm);
                sum += mm;
                counted++;
            }
            // 单日大雨或累计降雨达到阈值均跳过（有效降雨系数 0.8 已在决策服务处理，此处为策略硬条件）
            if (maxDay >= cfg.getForecastSkipMm().doubleValue()
                    || sum * 0.8 >= cfg.getForecastSkipMm().doubleValue()) {
                failures.add("未来 " + counted + " 日预报降雨 max=" + round(maxDay)
                        + "mm/累计" + round(sum) + "mm ≥ 阈值 " + cfg.getForecastSkipMm() + "mm");
            }
        }
        return failures;
    }

    private static double round(Double v) {
        return v == null ? 0d : java.math.BigDecimal.valueOf(v)
                .setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
