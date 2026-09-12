package com.farm.irrigation.config;

import com.farm.irrigation.control.ControlEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;

/**
 * Registers the control-engine evaluation cycle using either a cron expression
 * ({@code app.control.evaluate-cron}, e.g. "0 0/5 * * * ?" for every 5 minutes)
 * or, when the cron is blank, a fixed delay of
 * {@code app.control.evaluate-interval-ms} (default 5 minutes).
 */
@Configuration
public class ControlScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ControlScheduler.class);

    private final ControlEngine controlEngine;
    private final ControlProperties props;

    public ControlScheduler(ControlEngine controlEngine, ControlProperties props) {
        this.controlEngine = controlEngine;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        long initialDelayMs = 15_000;
        Trigger trigger;
        if (props.getEvaluateCron() != null && !props.getEvaluateCron().isBlank()) {
            String cron = props.getEvaluateCron().trim();
            log.info("Control evaluation scheduled with cron '{}'", cron);
            trigger = new CronTrigger(cron);
        } else {
            log.info("Control evaluation scheduled every {} ms", props.getEvaluateIntervalMs());
            PeriodicTrigger periodic = new PeriodicTrigger(Duration.ofMillis(props.getEvaluateIntervalMs()));
            periodic.setInitialDelay(Duration.ofMillis(initialDelayMs));
            trigger = periodic;
        }
        registrar.addTriggerTask(() -> {
            try {
                controlEngine.runCycle();
            } catch (Exception e) {
                log.error("Scheduled control cycle failed: {}", e.getMessage(), e);
            }
        }, trigger);
    }
}
