package com.farm.irrigation.service;

import com.farm.irrigation.config.InfluxProperties;
import com.influxdb.client.BucketsApi;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.OrganizationsApi;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.WriteErrorEvent;
import com.influxdb.query.FluxTable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InfluxService {

    private static final Logger log = LoggerFactory.getLogger(InfluxService.class);

    private final InfluxDBClient client;
    private final InfluxProperties props;
    /**
     * Single long-lived async write API. Points are buffered and flushed on a
     * background scheduler; an unreachable InfluxDB therefore never blocks the
     * MQTT dispatch / control path. Failures are logged via {@link WriteErrorEvent}.
     */
    private volatile WriteApi writeApi;

    public InfluxService(InfluxDBClient client, InfluxProperties props) {
        this.client = client;
        this.props = props;
    }

    @PostConstruct
    public void initWriter() {
        try {
            writeApi = client.makeWriteApi(WriteOptions.builder()
                    .batchSize(100)
                    .flushInterval(1000)
                    .bufferLimit(10_000)
                    .retryInterval(2_000)
                    .maxRetryTime(30_000)
                    .maxRetries(3)
                    .exponentialBase(2)
                    .build());
            writeApi.listenEvents(WriteErrorEvent.class, e ->
                    log.warn("InfluxDB async write error: {}", e.getThrowable().getMessage()));
        } catch (Exception e) {
            log.warn("InfluxDB write API init failed (telemetry time-series will be skipped): {}",
                    e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownWriter() {
        if (writeApi != null) {
            try {
                writeApi.close();
            } catch (Exception ignored) {
                // best-effort flush on shutdown
            }
        }
    }

    @PostConstruct
    public void ensureBucket() {
        if (!props.isEnsureBucket()) {
            return;
        }
        try {
            OrganizationsApi orgsApi = client.getOrganizationsApi();
            List<Organization> all = orgsApi.findOrganizations();
            List<Organization> orgs = all == null ? List.of()
                    : all.stream().filter(o -> props.getOrg().equals(o.getName())).toList();
            if (orgs.isEmpty()) {
                log.warn("InfluxDB org '{}' not found; skipping bucket auto-create. "
                        + "Create org/bucket manually or set INFLUX_ORG/TOKEN correctly.", props.getOrg());
                return;
            }
            String orgId = orgs.get(0).getId();
            BucketsApi bucketsApi = client.getBucketsApi();
            Bucket found = bucketsApi.findBucketByName(props.getBucket());
            if (found == null) {
                bucketsApi.createBucket(props.getBucket(), orgId);
                log.info("Created InfluxDB bucket '{}' in org '{}'", props.getBucket(), props.getOrg());
            } else {
                log.info("InfluxDB bucket '{}' ready", props.getBucket());
            }
        } catch (Exception e) {
            // InfluxDB temporarily unavailable must not prevent the application from booting
            log.warn("InfluxDB bucket ensure failed (will still attempt writes later): {}", e.getMessage());
        }
    }

    /** Asynchronously write a single point (never blocks the caller). */
    public void writePoint(Point point) {
        if (writeApi == null) {
            return;
        }
        try {
            writeApi.writePoint(point);
        } catch (Exception e) {
            log.warn("InfluxDB write enqueue failed: {}", e.getMessage());
        }
    }

    /** Asynchronously write a batch of points (never blocks the caller). */
    public void writePoints(List<Point> points) {
        if (writeApi == null || points == null || points.isEmpty()) {
            return;
        }
        try {
            writeApi.writePoints(points);
        } catch (Exception e) {
            log.warn("InfluxDB batch enqueue of {} points failed: {}", points.size(), e.getMessage());
        }
    }

    /**
     * Build a telemetry point.
     *
     * @param kind       measurement: soil / weather / flow / valve
     * @param deviceCode device tag
     * @param gatewaySn  gateway tag
     * @param values     numeric value map
     * @param ts         sample timestamp (record ts, for back-fill ordering)
     */
    public Point telemetryPoint(String kind, String deviceCode, String gatewaySn,
                                Map<String, Object> values, Instant ts) {
        Point point = Point.measurement(kind == null ? "telemetry" : kind)
                .addTag("deviceCode", deviceCode == null ? "unknown" : deviceCode)
                .addTag("gatewaySn", gatewaySn == null ? "unknown" : gatewaySn)
                .time(ts == null ? Instant.now() : ts, WritePrecision.MS);
        if (values != null) {
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) {
                    point.addField(e.getKey(), n.doubleValue());
                } else if (v instanceof Boolean b) {
                    point.addField(e.getKey(), b);
                } else if (v != null) {
                    point.addField(e.getKey(), String.valueOf(v));
                }
            }
        }
        return point;
    }

    /**
     * Aggregated query for one or more metrics of the devices attached to a field.
     *
     * @param measurements measurement names to search (typically all of soil/weather/flow/valve)
     * @param deviceCodes  devices to include
     * @param metrics      field names, comma separated
     * @param rangeFlux    e.g. "-24h"
     * @param aggWindow    e.g. "10m"; null/blank returns raw points
     */
    public FluxResult query(List<String> measurements, List<String> deviceCodes,
                            List<String> metrics, String rangeFlux, String aggWindow) {
        String flux = buildFlux(measurements, deviceCodes, metrics, rangeFlux, aggWindow);
        log.debug("Flux query: {}", flux);
        List<FluxTable> tables;
        try {
            tables = client.getQueryApi().query(flux, props.getOrg());
        } catch (Exception e) {
            log.warn("Flux query failed: {}", e.getMessage());
            throw new RuntimeException("influx query failed: " + e.getMessage(), e);
        }
        return toResult(tables, metrics);
    }

    private String buildFlux(List<String> measurements, List<String> deviceCodes,
                             List<String> metrics, String rangeFlux, String aggWindow) {
        StringBuilder sb = new StringBuilder();
        sb.append("from(bucket: \"").append(props.getBucket()).append("\")\n");
        sb.append("  |> range(start: ").append(rangeFlux).append(")\n");
        sb.append("  |> filter(fn: (r) => ");
        sb.append("r._measurement == \"").append(String.join("\" or r._measurement == \"", measurements)).append("\")\n");
        if (deviceCodes != null && !deviceCodes.isEmpty()) {
            sb.append("  |> filter(fn: (r) => r.deviceCode == \"")
              .append(String.join("\" or r.deviceCode == \"", deviceCodes)).append("\")\n");
        }
        sb.append("  |> filter(fn: (r) => r._field == \"")
          .append(String.join("\" or r._field == \"", metrics)).append("\")\n");
        if (aggWindow != null && !aggWindow.isBlank()) {
            sb.append("  |> aggregateWindow(every: ").append(aggWindow)
              .append(", fn: mean, createEmpty: false)\n");
        }
        sb.append("  |> sort(columns: [\"_time\"])\n");
        return sb.toString();
    }

    private FluxResult toResult(List<FluxTable> tables, List<String> metrics) {
        // Column order: ts first, then requested metrics in order, then device codes as extra columns.
        List<String> columns = new ArrayList<>();
        columns.add("ts");
        columns.addAll(metrics);
        Map<Instant, Map<String, Object>> byTime = new LinkedHashMap<>();
        for (FluxTable table : tables) {
            table.getRecords().forEach(rec -> {
                Instant t = rec.getTime() == null ? Instant.now() : rec.getTime();
                Map<String, Object> row = byTime.computeIfAbsent(t, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ts", k);
                    return m;
                });
                String field = rec.getField();
                Object val = rec.getValue();
                if (metrics.contains(field)) {
                    // if multiple devices report same metric at the same aggregated time, average
                    mergeNumeric(row, field, val);
                }
            });
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : byTime.values()) {
            List<Object> arr = new ArrayList<>();
            for (String col : columns) {
                arr.add(row.get(col));
            }
            rows.add(arr);
        }
        return new FluxResult(columns, rows);
    }

    private void mergeNumeric(Map<String, Object> row, String field, Object val) {
        if (!(val instanceof Number n)) {
            row.put(field, val);
            return;
        }
        Object existing = row.get(field);
        if (existing instanceof double[] avg) {
            avg[0] = (avg[0] * avg[1] + n.doubleValue()) / (avg[1] + 1);
            avg[1] += 1;
        } else {
            row.put(field, new double[]{n.doubleValue(), 1});
        }
    }

    /** Replace running averages with plain values before serialization. */
    public static class FluxResult {
        private final List<String> columns;
        private final List<List<Object>> rows;

        public FluxResult(List<String> columns, List<List<Object>> rowsRaw) {
            this.columns = columns;
            this.rows = new ArrayList<>();
            for (List<Object> r : rowsRaw) {
                List<Object> copy = new ArrayList<>();
                for (Object v : r) {
                    if (v instanceof double[] avg) {
                        copy.add(Math.round(avg[0] * 10000d) / 10000d);
                    } else if (v instanceof Instant t) {
                        copy.add(t.toString());
                    } else {
                        copy.add(v);
                    }
                }
                this.rows.add(copy);
            }
        }

        public List<String> getColumns() { return columns; }
        public List<List<Object>> getRows() { return rows; }
    }
}
