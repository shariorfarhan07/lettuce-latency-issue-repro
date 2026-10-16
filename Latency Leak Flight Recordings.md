redis/lettuce#3941 · reproduction · Java Flight Recorder

# Latency Leak Flight Recordings

Three 60-second JFR recordings of a Spring Boot app sending 500 custom `PING` commands per second through Lettuce. When a new keyword object is created per call, every command becomes a new metrics entry and the heap fills in seconds.

Spring Boot **3.5.5**Lettuce **6.6.0.RELEASE**JDK **17.0.20** Temurin, G1, **-Xmx2g**Redis **7.4**Recorded **2026-10-13**

## Findings

Heap kept after GC, built-in collector1,850 MiBafter 15 s and 7,512 commands; still held when load stopped

Kept per metrics entry≈ 256 kB(1,850 − 17) MiB ÷ 7,509 extra entries

Long-lived objects from the collector98.4%JFR old-object samples allocated in `Latencies.<init>`

- **Every command became its own entry.** With an anonymous `ProtocolKeyword` per call, tracked keys rose by about 500 per second, one per command. Keys read `commandType=com.example.repro.LoadGenerator$1@22d4bcaa`.
- **The memory is retained, not garbage.** After the app stopped sending commands at 90% heap, heap stayed at 1,855 MiB for the rest of the run. Only the collector's 10-minute reset or a restart frees it.
- **JFR names the owner.** 98.4% of sampled long-lived objects were allocated by `DefaultCommandLatencyCollector$Latencies.<init>`, and 97.6% of them are `long[]`, the HdrHistogram count arrays.
- **A stable keyword fixes it.** The same load with an `enum` keyword kept 3 entries and 17 MiB after GC for the whole minute.
- **Spring Boot's Micrometer recorder grows too, more slowly.** It registered a new timer pair per command (29,516 after 60 s) and never removed them. Heap after GC rose from 19 to 66 MiB.

## Setup

The app creates a `RedisClient` with Spring Boot's own `ClientResources` bean, so it uses exactly the latency recorder Spring Boot configured. A scheduler calls `dispatch(keyword, new StatusOutput<>(…))` 500 times per second against one Redis server. A reporter logs the recorder's key count and heap use every second without calling `retrieveMetrics()`, which would reset the map.

JFR ran with `settings=profile,path-to-gc-roots=true` for 60 seconds. Running out of memory also stops JFR's own threads, so the app stops sending commands once heap use passes 90%; that lets the recording be written. Times below count from the moment the load started.

| Run | Latency recorder | Keyword per call | Keys at 60 s | Result |
| --- | --- | --- | --- | --- |
| builtin-anonymous | `DefaultCommandLatencyCollector` | new anonymous class, no `toString()` | 7,512 | Grows until load stopped |
| micrometer-anonymous | `MicrometerCommandLatencyRecorder` | new anonymous class, no `toString()` | 29,516 | Grows the whole run |
| builtin-stable | `DefaultCommandLatencyCollector` | `ReproCommand.PING` enum | 3 | Flat |

The built-in collector is used when Spring Boot's `LettuceMetricsAutoConfiguration` is excluded and HdrHistogram and LatencyUtils are on the classpath. With Actuator present, Spring Boot installs the Micrometer recorder instead.

## Metrics entries over time

## Heap kept after each garbage collection

## What JFR says is holding the memory

JFR's old-object samples are objects that stayed alive for a long time, with the stack that allocated them. Allocation samples weight each allocation by its size. Both point at the latency recorder in the two leaking runs.

| Run | Top site of long-lived objects | Share | Top long-lived type | Share | Top allocation site (bytes) | Share |
| --- | --- | --- | --- | --- | --- | --- |

Each built-in entry holds two `LatencyStats` objects with four HdrHistograms each; every histogram's `long[]` counts array is 28,160 bytes. No `TimeCappedMovingAverageIntervalEstimator$PauseTracker` objects appear in either sample set, which fits Lettuce 6.6's default of no pause detector.

## Garbage collection

| Run | GCs after load start | Total pause | Longest pause |
| --- | --- | --- | --- |

All collections were G1 young or mixed pauses; there was no full GC. These runs do not show the 1–2 second pauses from the original report, because the load stopped at 90% heap before the collector ran out of room. The earlier run without that stop ended in `OutOfMemoryError` after 8 seconds on a 1 GiB heap.

## What this does and doesn't show

- It shows the mechanism with Spring Boot defaults on Lettuce 6.6.0: one remote address, default collector options (no pause detector, reset after each 10-minute publish), and a keyword object without a stable `toString()`.
- It does not show that a particular production service had this cause. That needs the service's Lettuce version, its keyword class, and the keys in its heap dump.
- Remote address changes were not exercised here; every key shares `/127.0.0.1`.
- The 500 commands per second rate and the 2 GiB heap set how fast it fails, not whether it fails. With this keyword pattern, the built-in collector needs about 250 kB per command until the next 10-minute reset.

## Open the recordings

`lettuce-latency-repro/jfr/builtin-anonymous.jfr`2.4 MB

`lettuce-latency-repro/jfr/micrometer-anonymous.jfr`1.8 MB

`lettuce-latency-repro/jfr/builtin-stable.jfr`1.3 MB

`lettuce-latency-repro/jfr/summary.json`from analyze-jfr.py

**JDK Mission Control:** open a `.jfr` file, then check *Memory → Old Object Sample* for the allocation stacks and paths to GC roots, *Memory → Allocations* grouped by class, and *Garbage Collections* for the heap-after-GC curve.

**Command line**, with the JDK's `jfr` tool:

```
jfr summary jfr/builtin-anonymous.jfr
jfr print --events jdk.OldObjectSample --stack-depth 20 jfr/builtin-anonymous.jfr
jfr print --events jdk.GCHeapSummary jfr/builtin-anonymous.jfr
python analyze-jfr.py          # rebuilds jfr/summary.json
./record-jfr.sh builtin-anonymous builtin anonymous 60   # records again (needs: docker compose up -d)
```