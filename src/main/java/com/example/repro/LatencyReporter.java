package com.example.repro;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.lettuce.core.metrics.CommandLatencyRecorder;
import io.lettuce.core.metrics.DefaultCommandLatencyCollector;
import io.lettuce.core.resource.ClientResources;

/**
 * Logs heap usage and the number of tracked latency keys once per second. Reads the collector's map without calling
 * {@code retrieveMetrics()}, which would reset it.
 */
@Component
@EnableScheduling
class LatencyReporter {

    private static final Logger log = LoggerFactory.getLogger(LatencyReporter.class);

    private final CommandLatencyRecorder recorder;

    private final LoadGenerator loadGenerator;

    private final ReproProperties properties;

    private final long startNanos = System.nanoTime();

    private boolean samplesLogged;

    LatencyReporter(ClientResources clientResources, LoadGenerator loadGenerator, ReproProperties properties) {
        this.recorder = clientResources.commandLatencyRecorder();
        this.loadGenerator = loadGenerator;
        this.properties = properties;
    }

    @Scheduled(fixedRate = 1000)
    void report() {

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<?, ?> latencies = collectorMap();
        int keys = latencies != null ? latencies.size() : -1;

        log.info(String.format("t=%3ds  tracked keys=%,8d  heap used=%,6d MiB / max %,d MiB",
                (System.nanoTime() - startNanos) / 1_000_000_000L, keys, heap.getUsed() >> 20, heap.getMax() >> 20));

        if (properties.stopAtHeapRatio() > 0 && heap.getUsed() > heap.getMax() * properties.stopAtHeapRatio()) {
            loadGenerator.stopLoad();
        }

        if (!samplesLogged && latencies != null && latencies.size() >= 3) {
            samplesLogged = true;
            log.info("Sample keys: {}", latencies.keySet().stream().limit(3).map(Object::toString)
                    .collect(Collectors.joining(", ")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> collectorMap() {

        if (!(recorder instanceof DefaultCommandLatencyCollector)) {
            return null;
        }

        try {
            Field field = DefaultCommandLatencyCollector.class.getDeclaredField("latencyMetricsRef");
            field.setAccessible(true);
            return ((AtomicReference<Map<?, ?>>) field.get(recorder)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read DefaultCommandLatencyCollector map", e);
        }
    }

}
