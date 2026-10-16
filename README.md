# lettuce-latency-issue-repro

A small Spring Boot application that reproduces unbounded memory growth in Lettuce's command
latency metrics, investigated in [redis/lettuce#3941](https://github.com/redis/lettuce/issues/3941).

> **In one line:** if an application dispatches a custom Redis command with a **new
> `ProtocolKeyword` object per call**, and that class doesn't override `toString()`, Lettuce
> creates a new metrics entry (about 250 KB) **for every command**. At 500 commands per second a
> 2 GiB heap runs out in about 18 seconds. A stable keyword (an `enum`) keeps memory flat.

| Scenario (500 commands/s, `-Xmx2g`, Lettuce defaults) | Metrics entries | Live heap | Outcome |
|---|---:|---|---|
| New keyword object per call | +500 per second | +120 MiB per second | `OutOfMemoryError` after about 18 s of load |
| `enum` keyword, reused | 3 | about 30 MiB after GC | stable |

## Contents

- [Quick start](#quick-start)
- [About DefaultCommandLatencyCollector](#about-defaultcommandlatencycollector)
- [What it causes](#what-it-causes)
- [Why it happens](#why-it-happens)
- [Why it should not be this way](#why-it-should-not-be-this-way)
- [Is this a design problem?](#is-this-a-design-problem)
- [What to do in an application](#what-to-do-in-an-application)
- [Better approaches in Lettuce](#better-approaches-in-lettuce)
- [What was tested](#what-was-tested)
- [Results](#results)
- [How the app works](#how-the-app-works)
- [How to run it](#how-to-run-it)
- [Earlier results on Spring Boot 3](#earlier-results-on-spring-boot-3)
- [Files](#files)
- [Troubleshooting](#troubleshooting)

## Quick start

Requires JDK 21+ and a Redis server on `localhost:6379` (for example `docker compose up -d`; see
[How to run it](#how-to-run-it) if Compose maps a random port).

```bash
./mvnw clean package -DskipTests
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=anonymous   # the bug
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=stable      # the fix
```

Watch the log: with `anonymous`, `tracked keys` rises by about 500 every second until the JVM runs
out of memory. With `stable`, it stays at 3.

---

## About DefaultCommandLatencyCollector

Facts in this section were checked against the Lettuce 6.8.2 jar.

### What it is

`io.lettuce.core.metrics.DefaultCommandLatencyCollector` is Lettuce's built-in, client-side
latency recorder. It measures how long each Redis command takes **as seen by the application**:
network, Redis processing and Netty handling together. It lives in `ClientResources`, so one
collector is shared by every client and connection built from those resources.

### What it collects

For every key `(local address*, remote address, command name)` it keeps:

| Data | Meaning |
|---|---|
| Count | Number of commands completed |
| **First response** latency | From writing the command to the first byte of the reply |
| **Completion** latency | From writing the command to the full reply being decoded |
| Min, max, percentiles | 50th, 90th, 95th, 99th and 99.9th percentile of each latency |
| Unit | Microseconds (default) |

\* The local address is only part of the key with `localDistinction(true)`.

The two latencies tell different stories. A large gap between first response and completion
points at big replies (large `HGETALL`, `LRANGE`, `KEYS`). A high first-response latency points at
the network or a busy Redis server.

### How it works

```text
 application ──dispatch/GET/SET──► CommandHandler ──write──► Redis
                                        │
                     reply decoded ◄────┘
                                        │ recordCommandLatency(local, remote, commandType,
                                        │                      firstResponseNanos, completionNanos)
                                        ▼
            DefaultCommandLatencyCollector
            Map<CommandLatencyId, Latencies>  (computeIfAbsent: new key → new entry)
                 └─ Latencies = 2 × LatencyStats (firstResponse, completion)
                       └─ each LatencyStats = 4 × HdrHistogram (~28 KB each)
                                        │
          every 10 minutes              │ retrieveMetrics()
          DefaultCommandLatencyEvent    │   1. read percentiles from each histogram
          Publisher                     │   2. if resetLatenciesAfterEvent: swap in an empty map
                                        ▼
            CommandLatencyEvent on ClientResources.eventBus()
                                        │
                                        ▼
            your subscriber (logs, custom metrics) — or nobody
```

1. **Recording.** When a reply is decoded, `CommandHandler` calls `recordCommandLatency(...)`. The
   collector builds a `CommandLatencyId` from the addresses and `commandType.toString()`, finds or
   creates the entry, and records both latencies into its histograms. Values above 5 minutes are
   capped.
2. **Histograms.** HdrHistogram stores latencies in fixed buckets, so recording is fast and
   allocation-free and percentiles are accurate, at the price of a fixed memory block per entry.
   LatencyUtils' `LatencyStats` wraps it and can optionally correct for JVM pauses (off by default
   since Lettuce 6.1.7).
3. **Publishing.** `DefaultCommandLatencyEventPublisher` calls `retrieveMetrics()` every 10 minutes,
   turns the histograms into `CommandMetrics` (count, min, max, percentiles) and publishes a
   `CommandLatencyEvent` on the event bus. It publishes even when nobody is subscribed.
4. **Reset.** With `resetLatenciesAfterEvent(true)` (the default), each publish replaces the map with
   an empty one, so every event covers one 10-minute window and old entries are dropped.

### When it is active

All three must be true:

- `CommandLatencyCollectorOptions.isEnabled()` is `true` (the default),
- HdrHistogram **and** LatencyUtils are on the classpath (both optional dependencies of Lettuce),
- nothing replaced the recorder in `ClientResources.builder().commandLatencyRecorder(...)`.

Otherwise Lettuce uses a no-op recorder. On Spring Boot 4 with Actuator, all three are true by
default (see [Why Spring Boot 4 uses the built-in collector](#why-spring-boot-4-uses-the-built-in-collector)).

### Why it exists and how it helps

- **Client-side truth.** Redis's own `SLOWLOG` and `LATENCY` only see time spent inside the
  server. The client sees what the application actually waits for, including network, TLS,
  connection queueing and reply decoding.
- **Per node and per command.** Keys include the remote address, so in a cluster or with replicas
  you can spot one slow node, or one slow command type, instead of a single blended average.
- **Tail latency.** Percentiles up to p99.9 show the slow outliers that averages hide, which is
  usually what users notice.
- **No extra infrastructure.** It works without Micrometer, Prometheus or an APM agent: subscribe to
  the event bus and log or forward the numbers.
- **Cheap per command.** Recording into an existing histogram is a few array writes, so the
  per-command overhead is negligible as long as the number of keys stays small.

### Using the data

Nothing reads the events unless you subscribe. A minimal subscriber:

```java
clientResources.eventBus().get()
        .filter(CommandLatencyEvent.class::isInstance)
        .cast(CommandLatencyEvent.class)
        .subscribe(event -> event.getLatencies().forEach((id, metrics) ->
                log.info("{} {}: count={} p99={}us", id.remoteAddress(), id.commandType(),
                        metrics.getCount(), metrics.getCompletion().getPercentiles().get(99.0))));
```

### Do you need it?

| Situation | Recommendation |
|---|---|
| You subscribe to `CommandLatencyEvent` or call `retrieveMetrics()` | Keep it; make sure every `ProtocolKeyword` has a stable `toString()` |
| You use Micrometer/Prometheus dashboards | Use `MicrometerCommandLatencyRecorder` with `MicrometerOptions` instead; it feeds your registry directly |
| You use tracing/APM for Redis spans | You probably don't need it; disable it |
| Nobody reads the numbers (most Spring Boot 4 apps) | Disable it with `CommandLatencyRecorder.disabled()`; it costs memory and gives nothing back |

### Options

Set with `DefaultCommandLatencyCollectorOptions.builder()` and
`ClientResources.builder().commandLatencyCollectorOptions(...)`:

| Option | Default | Effect |
|---|---|---|
| `enable()` / `disable()` | enabled | Turns collection on or off |
| `targetUnit(...)` | `MICROSECONDS` | Unit of published values |
| `targetPercentiles(...)` | 50, 90, 95, 99, 99.9 | Percentiles in each event |
| `resetLatenciesAfterEvent(...)` | `true` | Clear the map after each publish. `false` keeps every entry forever |
| `localDistinction(...)` | `false` | Also key by local address (more entries) |
| `usePauseDetector()` | off | Correct latencies for JVM pauses (adds a background thread) |

The publish interval is set separately with
`ClientResources.builder().commandLatencyPublisherOptions(DefaultEventPublisherOptions.builder().eventEmitInterval(...).build())`
(default 10 minutes; `DefaultEventPublisherOptions.disabled()` turns publishing **and** the reset off).

### Where it goes wrong

The design assumes **few keys**: a handful of command types times a handful of nodes. Each key
costs about 250 KB, and the only cleanup is the periodic reset. When the number of keys is driven
by something unbounded, such as a new keyword object per command, the map outgrows the heap long
before the reset. The rest of this README is about that case.

---

## What it causes

From the run to `OutOfMemoryError` on Lettuce 6.8.2 (see [Results](#results)):

1. **Heap grows in a straight line.** About 500 new entries and 120 MiB per second. All of it is
   live, so garbage collection can't free any of it.
2. **GC gets worse, then breaks down.** Young collections stop freeing memory. Near the limit G1
   falls back to back-to-back full GCs that free almost nothing: 8 full GCs in the last second,
   each 30–240 ms.
3. **The process dies.** `java.lang.OutOfMemoryError: Java heap space`. In a container whose memory
   limit is close to the heap size, the same growth ends in an OOM-kill of the container.
4. **Latency suffers before the crash.** Every command waits behind the GC pauses, so the
   application slows down for everyone before it fails, not only for the custom command.

The rate decides **how fast** this happens, not **whether** it happens. At 50 commands per second
the same heap would last about 3 minutes (estimated from 250 KB per entry, not measured); the
10-minute reset still comes too late.

The original production report on #3941 saw the same pattern: one `DefaultCommandLatencyCollector`
holding 94.75% of a 1 GB heap, and GC pauses rising from about 8 ms to 1.2–1.9 s before the
service was killed.

---

## Why it happens

When a reply arrives, Lettuce's `CommandHandler` reports the command's latency to the latency
recorder. `DefaultCommandLatencyCollector` keeps a map with one entry per key:

```text
CommandLatencyId = (local address*, remote address, commandType.toString())

* the local address is ignored unless localDistinction(true) is set
```

The command part of the key is the **string** returned by `ProtocolKeyword.toString()`:

- Built-in commands are `enum` constants (`CommandType.GET`), whose `toString()` is `"GET"`. Every
  `GET` lands in the same entry.
- A class that doesn't override `toString()` inherits `Object.toString()`, which returns
  `ClassName@hashCode`, for example `LoadGenerator$1@54abdb00`. Each new instance has a different
  name, so **each command creates a new entry**.

Note that Redis itself never sees the difference: `getBytes()` returns `PING` every time, so the
server receives the same command. Only the client-side metrics key changes.

### What one entry costs

Each entry holds:

- 2 `LatencyStats` objects (first-response latency and completion latency),
- each with 4 HdrHistograms covering 1 µs to 1 hour at 2 significant digits,
- each histogram backed by a 28,160-byte `long[]` counts array.

That is 8 × 28,160 B = 225,280 B of arrays, or about **250 KB per entry** with object overhead
(1,837 MiB live ÷ 7,507 entries in the JFR run).

### When entries are freed

| Configuration | Who frees entries |
|---|---|
| Lettuce defaults | The built-in publisher calls `retrieveMetrics()` every **10 minutes** and resets the map |
| Event publishing disabled, or `resetLatenciesAfterEvent(false)` | Nobody, unless the application calls `retrieveMetrics()` |

With a new keyword per call at 500 commands per second, a 10-minute window would need about
300,000 entries (about 75 GB), so the default reset never gets a chance to run.

### Why Spring Boot 4 uses the built-in collector

Spring Boot 3 had `LettuceMetricsAutoConfiguration`, which replaced Lettuce's recorder with
`MicrometerCommandLatencyRecorder` whenever Actuator was present. **Spring Boot 4 has no such
auto-configuration.** Its Lettuce support only adds tracing (`LettuceObservationAutoConfiguration`).
So `ClientResources` keeps Lettuce's default recorder, and because Actuator pulls in HdrHistogram
and LatencyUtils through Micrometer, that recorder is the real `DefaultCommandLatencyCollector`,
not the no-op one.

In other words: a Spring Boot 4 service with Actuator gets the histogram-based collector by
default, without asking for it and usually without knowing it is there.

---

## Why it should not be this way

The trigger is an application mistake, but the size of the consequence is out of proportion to it:

- **A metrics feature can crash the application.** Latency metrics are an observability add-on.
  Turning them on, or inheriting them, should never be able to take down the process they observe.
- **It is on by default and invisible.** `DEFAULT_ENABLED` is `true`. Nothing in
  `ClientResources.create()`, the Spring Boot docs or the logs says a histogram collector is
  running, so most users never configure it and never see it until production.
- **The contract is unwritten.** Nothing in `ProtocolKeyword`'s Javadoc says `toString()` must be
  stable. The interface only requires `getBytes()`, which this code implements correctly; the
  command it sends to Redis is correct.
- **The cost per mistake is huge.** One wrong `toString()` costs 250 KB per command, not a few
  bytes. Small, common mistakes (an anonymous class, a lambda-style helper, a record with extra
  fields) become out-of-memory errors within seconds.
- **The safety net is too slow.** The 10-minute reset bounds memory only if the number of distinct
  keys is small. It does nothing when keys are created per command.
- **Nothing reports it.** The collector never logs that it is tracking thousands of command
  names, even though no real Redis deployment has thousands of command types.

---

## Is this a design problem?

To be precise: **Redis the server is not involved.** It receives correct `PING` commands. The
question is about the **Lettuce** client's metrics design. The short answer is: the application
bug is real, but Lettuce's design turns it into an outage.

| Area | Design choice | Why it is a problem |
|---|---|---|
| Metrics key | Command name taken from `toString()` | `toString()` is a debugging method with no contract. The wire bytes (`getBytes()`) are the real command identity, and are already required to be correct |
| Collection size | Map has no cap on entries | Cardinality depends entirely on user input; one bad key source makes it unbounded |
| Cost per entry | Two `LatencyStats` with 8 full HdrHistograms per entry | About 250 KB per key is reasonable for 20 command types, not for 20,000 |
| Eviction | Only a time-based full reset (10 min) | A time bound doesn't bound size; there is no idle eviction or size limit |
| Default | Enabled whenever HdrHistogram/LatencyUtils are on the classpath | Those libraries arrive transitively (Micrometer), so users get the collector without opting in |
| Visibility | No warning when key count is abnormal | The failure is silent until GC pauses and OOM |

Where the maintainer's position is right: an entry cap on its own would **hide** the bug. The
application would keep creating new names and the metrics would be meaningless, just without the
crash. So the best fix removes the cause (unstable keys) first, and only then adds a guard.

Counterpoints worth stating fairly:

- Lettuce's built-in commands are all enums, so the default path is safe; the problem needs a
  custom `ProtocolKeyword`.
- Keying on `toString()` lets users choose readable metric names that differ from the bytes. Moving
  to `getBytes()` changes metric names for those users, which is why it needs a maintainer decision.

---

## What to do in an application

1. **Give custom commands a stable name.** Use an `enum`, or one shared instance whose `toString()`
   returns the command name, and reuse it:

   ```java
   enum MyCommands implements ProtocolKeyword {
       MYCMD;

       private final byte[] bytes = name().getBytes(StandardCharsets.US_ASCII);

       @Override
       public byte[] getBytes() {
           return bytes;
       }
   }

   commands.dispatch(MyCommands.MYCMD, new StatusOutput<>(StringCodec.UTF8));
   ```

   This repository's [`ReproCommand`](src/main/java/com/example/repro/ReproCommand.java) is the same
   pattern. Never build a `ProtocolKeyword` inside the call path.

2. **Turn off latency metrics if nothing reads them.** On Spring Boot 4 with Actuator, nothing
   reads them by default:

   ```java
   @Bean
   ClientResourcesBuilderCustomizer disableLettuceLatency() {
       return builder -> builder.commandLatencyRecorder(CommandLatencyRecorder.disabled());
   }
   ```

   Or without Spring:

   ```java
   ClientResources resources = DefaultClientResources.builder()
           .commandLatencyRecorder(CommandLatencyRecorder.disabled())
           .build();
   ```

3. **If you do use the metrics, keep the reset path on.** Leave event publishing enabled and
   `resetLatenciesAfterEvent` at `true`. If you disable publishing, call `retrieveMetrics()` on a
   schedule yourself.

### How to tell if you're affected

- Latency metrics or logs contain command names like `Foo$1@6d06d69c` instead of `GET`.
- A heap histogram is dominated by `long[]`, `org.HdrHistogram.Histogram` and
  `org.LatencyUtils.LatencyStats`:

  ```bash
  # Linux / macOS
  jmap -histo:live <pid> | grep -Ei "LatencyStats|HdrHistogram"

  # Windows
  jmap -histo:live <pid> | findstr /i "LatencyStats HdrHistogram"
  ```

- In a heap dump, `DefaultCommandLatencyCollector` retains most of the heap and its map keys show
  `commandType=...@<hash>`.

---

## Better approaches in Lettuce

Ordered from least to most invasive. The first two fix the cause; the rest limit the damage.

| # | Change | Effect | Trade-off |
|---|---|---|---|
| 1 | **Javadoc on `ProtocolKeyword`**: `toString()` must return a stable command name because metrics are keyed by it | Users learn the contract before production | Documentation only; relies on people reading it |
| 2 | **Key `CommandLatencyId` on `getBytes()`** (the wire name) instead of `toString()` | Removes the cause; per-call keyword objects collapse to one entry per real command | Changes metric names for keywords whose `toString()` differs from their bytes; needs a maintainer decision |
| 3 | **Warn on abnormal cardinality**: log once when the map passes, say, 1,000 distinct command names | Makes the problem visible in logs well before OOM | A log line, not a fix |
| 4 | **Size guard with an overflow bucket**: past a limit, record new names under one `OTHER` entry instead of creating new entries | Memory is bounded and the overflow is visible in the metrics | Loses per-command detail past the limit; should be paired with #3 so the bug isn't hidden |
| 5 | **Accurate Javadoc on `CommandLatencyCollectorOptions`**: when the map can grow without limit (reset disabled, publisher disabled, unstable names) | Users who change defaults know the risk | Documentation only |
| 6 | **Lighter default histograms** (fewer intervals, or lower range/precision) | Each entry costs less, so any leak is slower | Less detailed latency data |

A reasonable combination is **#1 + #2** (fix the cause), plus **#3** so any remaining case is loud.
**#4** without #3 is what the maintainer warned against: it would silence the bug instead of
exposing it.

---

## What was tested

Run on 2026-10-16 on one Windows 11 machine.

| Component | Version |
|---|---|
| Spring Boot | 4.0.6 (same dependencies as the production service, see [`pom.xml`](pom.xml)) |
| Lettuce | 6.8.2.RELEASE (managed by Spring Boot) |
| HdrHistogram / LatencyUtils | 2.2.2 / 2.0.3, transitive through `spring-boot-starter-actuator` |
| Latency recorder | `io.lettuce.core.metrics.DefaultCommandLatencyCollector` |
| Lettuce options | All defaults: no changes to `usePauseDetector`, `resetLatenciesAfterEvent` or the emit interval |
| JDK | Temurin 25.0.3, G1 (the pom targets 21; no JDK 21 was installed) |
| Redis | Local stand-in server that answers `PING` (Docker was not running) |

The stand-in server affects latency values only. Entry count and memory per entry depend on the
client, not the server. Rerun against real Redis before quoting the numbers elsewhere.

Scenarios, each sending `PING` through `dispatch(...)` at **500 commands per second** with `-Xmx2g`:

| Name | Keyword passed to `dispatch` | Heap guard |
|---|---|---|
| `boot4-anonymous` | `new ProtocolKeyword() { getBytes() }` per call | Load stops at 90% heap, 60 s JFR |
| `boot4-stable` | `ReproCommand.PING` (an `enum`) | Same, 60 s JFR |
| `boot4-oom` | `new ProtocolKeyword() { getBytes() }` per call | None; runs until `OutOfMemoryError` |

---

## Results

### 1. Run to `OutOfMemoryError`

```text
Sending 500 PING/s using ANONYMOUS keyword; latency recorder: io.lettuce.core.metrics.DefaultCommandLatencyCollector
t=  1s  tracked keys=      10  heap used=    60 MiB / max 2,048 MiB
t=  4s  tracked keys=   1,510  heap used=   403 MiB / max 2,048 MiB
t=  8s  tracked keys=   3,510  heap used=   894 MiB / max 2,048 MiB
t= 12s  tracked keys=   5,510  heap used= 1,382 MiB / max 2,048 MiB
t= 16s  tracked keys=   7,510  heap used= 1,864 MiB / max 2,048 MiB
t= 18s  tracked keys=   8,449  heap used= 2,003 MiB / max 2,048 MiB
Terminating due to java.lang.OutOfMemoryError: Java heap space
```

End of the GC log, the last second before death:

```text
[20.468s] Pause Full (G1 Compaction Pause) 2041M->2041M(2048M) 38.958ms
[20.512s] Pause Full (G1 Compaction Pause) 2045M->2045M(2048M) 41.412ms
[20.794s] Pause Full (G1 Compaction Pause) 2046M->2003M(2048M) 242.547ms
[20.931s] Pause Full (G1 Compaction Pause) 2024M->2024M(2048M) 51.365ms
```

- One new entry per command, about 120 MiB per second.
- 8 full GCs in the final second, each freeing almost nothing; longest pause 242 ms.
- JVM exit code 3, about 21 s after launch.

### 2. JFR runs (load stopped at 90% heap)

| Time | Anonymous: keys | Anonymous: heap used | Stable: keys | Stable: heap used |
|---|---:|---:|---:|---:|
| 1 s | 507 | 152 MiB | 3 | 75 MiB |
| 4 s | 2,008 | 526 MiB | 3 | 88 MiB |
| 8 s | 4,008 | 1,015 MiB | 3 | 99 MiB |
| 10 s | 5,007 | 1,261 MiB | 3 | 104 MiB |
| ~15 s | 7,507 (load stopped) | 1,858 MiB | 3 | ~118 MiB |
| 60 s | 7,507 | 1,859 MiB | 3 | 56 MiB |

| Metric (60 s JFR) | Anonymous | Stable |
|---|---:|---:|
| GC count | 44 (36 young, 8 old) | 16 (14 young, 2 old) |
| Pause median | 5.2 ms | 5.3 ms |
| Pause p95 | 36.0 ms | 8.1 ms |
| Pause max | 39.4 ms | 8.1 ms |
| Total pause time | 434 ms | 75 ms |
| Heap after GC, at end | **1,837 MiB** | **30 MiB** |

After the load stopped, the anonymous heap **stayed at 1,859 MiB for 45 seconds**. The memory is
retained, not garbage; only the 10-minute reset or a restart frees it.

Sample keys from the collector's map:

```text
[local:any -> localhost/127.0.0.1:6379, commandType=PING]
[local:any -> localhost/127.0.0.1:6379, commandType=CLIENT]
[local:any -> localhost/127.0.0.1:6379, commandType=com.example.repro.LoadGenerator$1@54abdb00]
```

Every key has the same remote address, so this is not IP rotation.

### 3. Answers to the maintainer's questions

| Question on #3941 | What this reproduction shows |
|---|---|
| Lettuce version | 6.8.2.RELEASE (Spring Boot 4.0.6) |
| Keyword passed to `dispatch(...)` | Anonymous `ProtocolKeyword` per call, no `toString()` override |
| Changed `usePauseDetector`, `resetLatenciesAfterEvent` or the emit interval? | No; all defaults |
| Entries in the map, sample keys | About 500 new entries per second; keys like `LoadGenerator$1@54abdb00` |
| Many remote addresses? | No, a single one |

These describe this reproduction, not the production service. Confirming the production cause
needs the service's keyword class and the keys in its heap dump.

---

## How the app works

```text
src/main/java/com/example/repro/
├── ReproApplication.java    Spring Boot entry point
├── ReproProperties.java     repro.keyword, repro.rate, repro.stop-at-heap-ratio
├── ReproCommand.java        the fix: enum ProtocolKeyword with a stable name
├── LoadGenerator.java       sends PING at a fixed rate through dispatch(...)
└── LatencyReporter.java     logs tracked keys and heap use every second
src/main/resources/
└── application.properties   defaults (keyword anonymous, rate 500) and excluded infrastructure
compose.yaml       Redis for the app
record-jfr.sh      records one scenario with Java Flight Recorder
analyze-jfr.py     turns the Spring Boot 3 recordings into jfr/summary.json
REPORT.md          the Spring Boot 4 run report
```

- [`LoadGenerator`](src/main/java/com/example/repro/LoadGenerator.java) builds a `RedisClient` from
  Spring Boot's own `ClientResources` bean, so it uses exactly the recorder Spring Boot configured.
  A single scheduler thread calls `commands.dispatch(keyword, new StatusOutput<>(StringCodec.UTF8))`
  every 2 ms. `repro.keyword=anonymous` creates a new keyword per call (**the bug**);
  `repro.keyword=stable` returns `ReproCommand.PING` (**the fix**).
- [`LatencyReporter`](src/main/java/com/example/repro/LatencyReporter.java) logs heap use and the
  size of the collector's internal map once per second. It reads the map by reflection and does
  **not** call `retrieveMetrics()`, which would reset it. If `repro.stop-at-heap-ratio` is above 0,
  it stops the load past that fraction of the heap so a JFR recording can still be written.
- [`application.properties`](src/main/resources/application.properties) excludes DataSource, JPA,
  SQS and SSM Parameter Store auto-configuration and sets a dummy AWS region, so the app starts with
  only Redis. None of these touch Lettuce.

---

## How to run it

Requirements: JDK 21+ and Redis. On Windows, use Git Bash for the `.sh` script.

```bash
./mvnw clean package -DskipTests
docker compose up -d
port=$(docker compose port redis 6379 | sed 's/.*://')

# the bug
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=anonymous --spring.data.redis.port=$port

# the fix
java -Xmx2g -jar target/tep-filter.jar --repro.keyword=stable --spring.data.redis.port=$port

# run until the JVM dies, with a GC log
java -Xmx2g -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=jfr/boot4-oom-gc.log \
     -jar target/tep-filter.jar --repro.keyword=anonymous --spring.data.redis.port=$port

# JFR recordings (load stops at 90% heap)
./record-jfr.sh boot4-anonymous anonymous 60
./record-jfr.sh boot4-stable    stable    60

docker compose down
```

Change the rate with `--repro.rate=<commands per second>`. To try another Lettuce version, add
`<lettuce.version>` to the pom's `<properties>` (Spring Boot's dependency management reads it).

---

## Earlier results on Spring Boot 3

Before the pom was switched to the production dependencies, the same reproduction ran on Spring
Boot 3.5.5 with Lettuce 6.6.0 and JDK 17 against real Redis in Docker. Those results agree with the
Spring Boot 4 runs:

| Scenario | Keys at 60 s | Heap after GC | Longest pause |
|---|---:|---|---:|
| Built-in collector, new keyword per call | 7,512 | 136 → **1,850 MiB** within 15 s | 28 ms |
| Micrometer recorder, new keyword per call | 29,516 | 19 → **66 MiB** over 54 s | 18 ms |
| Built-in collector, `enum` keyword | 3 | **17 MiB**, flat | 3 ms |

- JFR's old-object samples put **98.4%** of long-lived objects at
  `DefaultCommandLatencyCollector$Latencies.<init>`, 97.6% of them `long[]` (histogram arrays).
- The Micrometer recorder (Spring Boot 3's default with Actuator) also grows without limit, more
  slowly, at about 1.8 KiB per command, and floods the metrics backend with new tag values.
- A run without the heap guard ended in a GC death spiral: 81 full GCs in the last 3.5 seconds,
  84% of the time paused, longest pause 224 ms ([`hs_err_pid33784.jfr`](hs_err_pid33784.jfr)).

Full details are in [`Latency Leak Flight Recordings.md`](Latency%20Leak%20Flight%20Recordings.md).

---

## Files

| Path | What it is |
|---|---|
| [`REPORT.md`](REPORT.md) | Spring Boot 4 run report |
| [`jfr/boot4-anonymous.jfr`](jfr/boot4-anonymous.jfr), [`.log`](jfr/boot4-anonymous.log) | JFR and app log, new keyword per call |
| [`jfr/boot4-stable.jfr`](jfr/boot4-stable.jfr), [`.log`](jfr/boot4-stable.log) | JFR and app log, `enum` keyword |
| [`jfr/boot4-oom.log`](jfr/boot4-oom.log), [`jfr/boot4-oom-gc.log`](jfr/boot4-oom-gc.log) | Run to `OutOfMemoryError`: app log and GC log |
| [`jfr/builtin-*.jfr`](jfr), [`jfr/micrometer-anonymous.jfr`](jfr/micrometer-anonymous.jfr) | Spring Boot 3 recordings |
| [`hs_err_pid33784.jfr`](hs_err_pid33784.jfr) | Spring Boot 3 emergency JFR from the run that went fully out of memory |
| [`jfr/summary.json`](jfr/summary.json), [`jfr/oom-emergency-summary.json`](jfr/oom-emergency-summary.json) | `analyze-jfr.py` output for the Spring Boot 3 recordings |
| [`Latency Leak Flight Recordings.md`](Latency%20Leak%20Flight%20Recordings.md) | Full Spring Boot 3 report |

### Opening a recording

In **JDK Mission Control**, open a `.jfr` file and check *Memory → Old Object Sample*,
*Memory → Allocations* and *Garbage Collections*. From the command line:

```bash
jfr summary jfr/boot4-anonymous.jfr
jfr print --events jdk.GCHeapSummary jfr/boot4-anonymous.jfr
```

---

## Troubleshooting

- **`release version 21 not supported`**: Maven is running on an older JDK. Point `JAVA_HOME` at
  JDK 21 or newer.
- **`Unable to load region from any of the providers`**: an AWS auto-configuration is active. Keep
  the exclusions and `spring.cloud.aws.region.static` in `application.properties`.
- **The app hangs at startup**: Redis isn't reachable on the configured host and port.
- **`failed to connect to the docker API`**: start Docker Desktop before `docker compose up -d`.
- **The `.jfr` file is 0 bytes**: the JVM ran completely out of memory and JFR's threads died with
  it. Keep `--repro.stop-at-heap-ratio=0.9` (as `record-jfr.sh` does).
