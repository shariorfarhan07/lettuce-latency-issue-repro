#!/usr/bin/env bash
# Records one scenario with Java Flight Recorder.
# Usage: ./record-jfr.sh <name> <keyword: anonymous|stable> [seconds]
# Needs Redis from compose.yaml: docker compose up -d
set -euo pipefail

name=$1
keyword=$2
seconds=${3:-60}

cd "$(dirname "$0")"
mkdir -p jfr

port=$(docker compose port redis 6379 | sed 's/.*://')
jar=$(ls target/tep-filter.jar | head -1)

# Load stops at 90% heap so JFR's own threads don't run out of memory; "timeout" ends the JVM after the recording is written.
timeout $((seconds + 15)) java -Xmx2g \
  "-XX:StartFlightRecording=name=$name,settings=profile,path-to-gc-roots=true,duration=${seconds}s,dumponexit=true,filename=jfr/$name.jfr" \
  -jar "$jar" \
  --repro.keyword="$keyword" \
  --repro.stop-at-heap-ratio=0.9 \
  --spring.data.redis.host=localhost \
  --spring.data.redis.port="$port" \
  > "jfr/$name.log" 2>&1 || true

ls -l "jfr/$name.jfr"
