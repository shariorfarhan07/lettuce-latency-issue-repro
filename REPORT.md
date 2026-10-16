# DefaultCommandLatencyCollector memory growth: reproduction report

Related issue: redis/lettuce#3941. Run date: 2026-10-16.

## Summary

If a new `ProtocolKeyword` object is passed to `dispatch(...)` on every call, and that keyword does not override `toString()`, every command creates its own entry in `DefaultCommandLatencyCollector`. Each entry holds about **250 KB**. At 500 commands/s the heap grows by about **122 MiB/s**, and a 2 GiB heap ran out of memory **18 seconds** after load started.

Reusing one stable keyword (an enum) keeps the collector at **3 entries**. Heap after GC stays at about **30 MiB**.

The cause is the keyword, not IP rotation or a missing metrics consumer. This matches the maintainer's analysis of #1210.

## Environment

| Item | Value |
|---|---|
| Spring Boot | 4.0.6, same dependencies as the production service (`pom.xml`) |
| Lettuce | 6.8.2.RELEASE |
| HdrHistogram / LatencyUtils | 2.2.2 / 2.0.3, both transitive through `spring-boot-starter-actuator` (Micrometer) |
| Latency recorder in use | `io.lettuce.core.metrics.DefaultCommandLatencyCollector` |
| Lettuce options | All defaults: no changes to `usePauseDetector`, `resetLatenciesAfterEvent` or the event emit interval |
| JVM | Temurin 25.0.3, G1, `-Xmx2g` |
| Load | 500 async `PING`/s on one connection |
| Redis | Local stand-in server that answers `PING` (Docker was not available) |

### Why the built-in collector is active on Boot 4

Spring Boot 3 had `LettuceMetricsAutoConfiguration`, which replaced Lettuce's recorder with `MicrometerCommandLatencyRecorder`. Spring Boot 4 does not have it. Its metrics module registers nothing for Lettuce; only `LettuceObservationAutoConfiguration` (tracing) remains. So `ClientResources` keeps Lettuce's default recorder. Because actuator brings in HdrHistogram and LatencyUtils, that recorder is the real `DefaultCommandLatencyCollector`, not the no-op one.

## Scenarios

| Name | Keyword passed to `dispatch(...)` | Heap guard | Duration |
|---|---|---|---|
| `boot4-anonymous` | `new ProtocolKeyword() { getBytes() }` per call, no `toString()` | Load stops at 90% heap | 60 s JFR |
| `boot4-stable` | `ReproCommand.PING` (enum, reused) | Same | 60 s JFR |
| `boot4-anonymous-oom` | Same as `boot4-anonymous` | None, `-XX:+ExitOnOutOfMemoryError` | Until OOM |

The tracked-key count is read from the collector's internal map by reflection, without calling `retrieveMetrics()`, so reading it does not reset the map.

## Results

### Tracked entries and heap, per second

| Time | Anonymous: keys | Anonymous: heap used | Stable: keys | Stable: heap used |
|---|---|---|---|---|
| 1 s | 507 | 152 MiB | 3 | 75 MiB |
| 2 s | 1,007 | 276 MiB | 3 | 82 MiB |
| 4 s | 2,008 | 526 MiB | 3 | 88 MiB |
| 6 s | 3,007 | 772 MiB | 3 | 94 MiB |
| 8 s | 4,008 | 1,015 MiB | 3 | 99 MiB |
| 10 s | 5,007 | 1,261 MiB | 3 | 104 MiB |
| ~15 s | 7,507 (load stopped at 90%) | 1,858 MiB | 3 | ~118 MiB |
| 60 s | 7,507 | 1,859 MiB | 3 | 56 MiB (after a GC) |

Stable heap use rises and falls as normal garbage is created and collected. The anonymous heap never comes back down.

### Garbage collection (JFR, 60 s)

| Metric | Anonymous | Stable |
|---|---|---|
| GC count | 44 (36 young, 8 old) | 16 (14 young, 2 old) |
| Pause median | 5.2 ms | 5.3 ms |
| Pause p95 | 36.0 ms | 8.1 ms |
| Pause max | 39.4 ms | 8.1 ms |
| Total pause time | 434 ms | 75 ms |
| Heap after GC: max / end | **1,837 MiB / 1,837 MiB** | **30 MiB / 30 MiB** |

Heap after GC is the live set. In the anonymous run almost all of it is retained by the collector.

### Time to OutOfMemoryError (no guard)

| Time | Keys | Heap used |
|---|---|---|
| 12 s | 5,509 | 1,382 MiB |
| 16 s | 7,509 | 1,862 MiB |
| 17 s | 8,009 | 1,979 MiB |
| 18 s | 8,461 | 2,003 MiB |
| — | `java.lang.OutOfMemoryError: Java heap space`, JVM exit code 3 | |

### Cost per entry

1,837 MiB live ÷ 7,507 entries ≈ **250 KB per entry**. Each entry holds two `LatencyStats` objects, and most of that memory is their HdrHistogram buffers.

### Sample keys

```
[local:any -> localhost/127.0.0.1:6379, commandType=PING]
[local:any -> localhost/127.0.0.1:6379, commandType=CLIENT]
[local:any -> localhost/127.0.0.1:6379, commandType=com.example.repro.LoadGenerator$1@54abdb00]
```

Each `LoadGenerator$1@<hash>` key is a separate entry for the same `PING` command. The remote address never changes, so IP rotation plays no part here.

## Analysis

1. `CommandLatencyId` uses `commandType.toString()` as the command name. An anonymous class that does not override `toString()` returns `ClassName@identityHash`, so every instance becomes a new key. That is one new entry per command.
2. The default `DefaultCommandLatencyEventPublisher` resets the map every 10 minutes. At 500 commands/s that window would hold about 300,000 entries, or about 75 GB at 250 KB each. The heap runs out in seconds, long before the first reset, so the reset does not help here.
3. With a stable keyword, the map holds one entry per real command type (`PING`, `CLIENT`, `HELLO`) and stays flat.

## Correction to the original report

- The map is reset without a consumer, by the default event publisher every 10 minutes.
- IP rotation adds only a few entries per reconnect and cannot explain growth of hundreds of MB per second.
- The real cause is a per-call `ProtocolKeyword` without a stable `toString()`.

## Recommendations

**In the application:**

1. Use one stable keyword per command, for example an enum, as in `ReproCommand`:

   ```java
   public enum ReproCommand implements ProtocolKeyword {
       PING;
       private final byte[] bytes = name().getBytes(StandardCharsets.US_ASCII);
       @Override public byte[] getBytes() { return bytes; }
   }
   ```

   If a class is needed instead, override `toString()` to return the command name and reuse the instance.
2. If the latency metrics are not used, disable the recorder:

   ```java
   DefaultClientResources.builder()
           .commandLatencyRecorder(CommandLatencyRecorder.disabled())
           .build();
   ```

**For Lettuce (upstream PR), in line with the maintainer's comment:**

1. Javadoc on `ProtocolKeyword` saying that `toString()` must return a stable command name, because metrics are keyed by it.
2. Accurate Javadoc on `CommandLatencyCollectorOptions` about when the map can grow without limit: with `resetLatenciesAfterEvent(false)`, with the publisher disabled, or with unstable keyword names.
3. Possibly key `CommandLatencyId` on `getBytes()`. This needs a maintainer decision because it changes metric names.

An entry cap or TTL (the current PR #3944) is not recommended. It would hide this bug instead of fixing it.

## Caveats

- **Redis stand-in.** The runs used a local server that answers `PING`, not real Redis, because Docker was not running. Latency values differ from real Redis, but entry count and memory per entry do not depend on the server. Rerun with `docker compose up -d` before quoting numbers upstream.
- **JDK.** The pom targets Java 21, but the build and runs used JDK 25 because no JDK 21 is installed.
- **Infrastructure switched off.** DataSource, JPA, SQS and SSM Parameter Store auto-configuration were excluded so the app starts with only Redis. They do not touch Lettuce.

## How to reproduce

```bash
./mvnw clean package -DskipTests                 # needs JDK 21+
docker compose up -d                             # Redis
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=anonymous --repro.stop-at-heap-ratio=0.9
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=stable
# With JFR:
./record-jfr.sh boot4-anonymous anonymous 60
./record-jfr.sh boot4-stable stable 60
```

Raw output: `jfr/boot4-anonymous.{jfr,log}`, `jfr/boot4-stable.{jfr,log}`, `jfr/boot4-anonymous-oom.log`.
