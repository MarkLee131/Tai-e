#!/usr/bin/env bash
# E6 — full-suite cold-cache variance: 3 independent live passes over all 11
# DaCapo benchmarks. Each pass sets a fresh -PreflCacheSalt so every oracle
# query misses the cache and goes live, giving an end-to-end fresh-oracle draw.
#
# Output: eval/arm2/raw/expStabFull_<salt>.xml (the JUnit XML embeds the
# harness stdout: per-benchmark recall rows, [oracle] cost lines, [disposer]
# stats). Parse with scripts/stability_report.py.
#
# Budget guard: a cold cache is expected to cost <= ~$0.1/pass (~300 live
# queries). Any single benchmark's [oracle] line exceeding $0.05 signals a
# cache-salt misconfiguration (e.g. salt not reaching the cache key would show
# live=0; per-fire re-querying would blow the per-benchmark cost) => abort.
set -uo pipefail
cd "$(dirname "$0")/../../.."          # -> Tai-e/

BENCH11=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse
XML=build/test-results/reflRecall/TEST-pascal.taie.analysis.pta.ReflectionRecallEval.xml
GUARD=0.05                             # max $ per benchmark [oracle] line

SALTS="${SALTS:-stab5 stab6 stab7}"
for salt in $SALTS; do
  echo "=== pass $salt: full 11-benchmark reflRecall, cold cache ==="
  ./gradlew reflRecall -PreflBenchmarks="$BENCH11" -ParmLive \
    -PreflCacheSalt="$salt" -x javadoc --console=plain
  status=$?
  if [[ ! -f "$XML" ]]; then
    echo "ABORT: $XML missing after pass $salt (gradle exit $status)" >&2
    exit 1
  fi
  out="eval/arm2/raw/expStabFull_${salt}.xml"
  cp "$XML" "$out"
  if [[ $status -ne 0 ]]; then
    echo "ABORT: gradle exited $status on pass $salt; XML kept at $out" >&2
    exit "$status"
  fi
  # Budget guard: no single [oracle] line may exceed $GUARD.
  over=$(grep -oE '\[oracle\][^<&]*' "$out" \
         | awk -v g="$GUARD" '{c=$0; sub(/.*cost=\$/,"",c); if (c+0 > g+0) print}')
  if [[ -n "$over" ]]; then
    echo "ABORT: per-benchmark oracle cost above \$$GUARD in pass $salt" >&2
    echo "       (cache-salt misconfiguration signal):" >&2
    echo "$over" >&2
    exit 2
  fi
  total=$(grep -oE 'cost=\$[0-9.]+' "$out" | sed 's/cost=\$//' \
          | awk '{s+=$1} END {printf "%.4f", s}')
  echo "--- pass $salt done: $(grep -c '\[oracle\]' "$out") oracle lines, total cost \$$total"
done
echo "=== all 3 stability passes complete ==="
