#!/usr/bin/env bash
# Adversarial-oracle corruption sweep (RQ-adv). Warm cache => zero live cost:
# corruption is applied AFTER the (cached) delegate answers.
#
# Env:
#   BENCHES  comma list of benchmarks (default: luindex,antlr,hsqldb,fop)
#   QUICK=1  validation mode: baseline pass + {random 0.50,1.00; loadable 1.00;
#            silent 1.00}, seed 1 only. Used for fast end-to-end checks of the
#            pipeline; the default (QUICK unset) is the full paper matrix.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> Tai-e/
BENCHES="${BENCHES:-luindex,antlr,hsqldb,fop}"
DUMP=eval/arm2/raw/reachdumps
GFLAGS=(-x javadoc --console=plain)

point() {  # mode rate seed
  local mode=$1 rate=$2 seed=$3
  ./gradlew reflRecall -PreflBenchmarks="$BENCHES" -ParmLive -PreflConfigs=llm \
    -PreflCorrupt="$mode" -PreflCorruptRate="$rate" -PreflCorruptSeed="$seed" \
    -PreflDumpReach="$DUMP" -PreflDumpTag="$mode-$rate-s$seed" "${GFLAGS[@]}"
}

# 1. Baselines + clean llm run (all configs, dumps everything incl. GT ingredients).
./gradlew reflRecall -PreflBenchmarks="$BENCHES" -ParmLive \
  -PreflDumpReach="$DUMP" -PreflDumpTag=clean "${GFLAGS[@]}"

if [[ "${QUICK:-0}" == "1" ]]; then
  point random   0.50 1
  point random   1.00 1
  point loadable 1.00 1
  point silent   1.00 1
else
  # 2. Sweep: mode x rate (seed 1), plus 3 seeds at rate 0.50 for jitter bars.
  for mode in random loadable silent; do
    for rate in 0.25 0.50 0.75 1.00; do
      point "$mode" "$rate" 1
    done
    for seed in 2 3; do
      point "$mode" 0.50 "$seed"
    done
  done
fi
echo "sweep done: $(ls "$DUMP" | wc -l) dumps"
