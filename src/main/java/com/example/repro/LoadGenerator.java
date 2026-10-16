package com.example.repro;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.resource.ClientResources;

/**
 * Dispatches a custom PING command at a fixed rate through a {@link RedisClient} that uses Spring Boot's
 * {@link ClientResources} bean, so the latency recorder is exactly the one Spring Boot configured.
 */
@Component
class LoadGenerator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);

    private final ClientResources clientResources;

    private final DataRedisConnectionDetails connectionDetails;

    private final ReproProperties properties;

    private RedisClient client;

    private StatefulRedisConnection<String, String> connection;

    private ScheduledExecutorService scheduler;

    private volatile boolean running;

    LoadGenerator(ClientResources clientResources, DataRedisConnectionDetails connectionDetails, ReproProperties properties) {
        this.clientResources = clientResources;
        this.connectionDetails = connectionDetails;
        this.properties = properties;
    }

    @Override
    public void start() {

        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        client = RedisClient.create(clientResources, RedisURI.create(standalone.getHost(), standalone.getPort()));
        connection = client.connect();
        RedisAsyncCommands<String, String> commands = connection.async();

        log.info("Sending {} PING/s using {} keyword; latency recorder: {}", properties.rate(), properties.keyword(),
                clientResources.commandLatencyRecorder().getClass().getName());

        long periodMicros = Math.max(1, 1_000_000L / properties.rate());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "load-generator"));
        scheduler.scheduleAtFixedRate(() -> commands.dispatch(keyword(), new StatusOutput<>(StringCodec.UTF8)), 0,
                periodMicros, TimeUnit.MICROSECONDS);
        running = true;
    }

    private ProtocolKeyword keyword() {

        if (properties.keyword() == ReproProperties.KeywordMode.STABLE) {
            return ReproCommand.PING;
        }

        // The bug: a new keyword object per call that does not override toString(),
        // so every call is reported as a different command name, e.g. "LoadGenerator$1@6d06d69c".
        return new ProtocolKeyword() {

            @Override
            public byte[] getBytes() {
                return PING;
            }

        };
    }

    /**
     * Stop sending commands but keep the application running.
     */
    void stopLoad() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.warn("Load stopped before the heap ran out so the JFR recording can be written");
        }
    }

    @Override
    public void stop() {

        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

}
