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
  # 2. Sweep: seeds 1-3 at every rate for random/loadable (the per-site corruption
  #    decision is a deterministic hash of (siteId, seed), so with few residual
  #    sites per benchmark the rate axis is coarse; multiple seeds expose the
  #    per-site Bernoulli structure). silent needs seed 1 only (seed-independent
  #    outcome: every answer is emptied regardless of the hash draw at rate 1.0,
  #    and lower silent rates are covered by the seed-1 draw).
  for mode in random loadable; do
    for rate in 0.25 0.50 0.75 1.00; do
      for seed in 1 2 3; do
        point "$mode" "$rate" "$seed"
      done
    done
  done
  for rate in 0.25 0.50 0.75 1.00; do
    point silent "$rate" 1
  done
  for seed in 2 3; do
    point silent 0.50 "$seed"
  done
  # 3. Extra clean draws: the clean reach set itself has schedule-dependent
  #    attractors (2026-07-10 finding: fire-time prompts select among cached
  #    answer variants; fop flips by ~16 methods). adversarial_report.py measures
  #    pollution against the UNION of all clean draws, so take three.
  for tag in clean-r2 clean-r3; do
    ./gradlew reflRecall -PreflBenchmarks="$BENCHES" -ParmLive -PreflConfigs=llm \
      -PreflDumpReach="$DUMP" -PreflDumpTag="$tag" "${GFLAGS[@]}"
  done
fi
echo "sweep done: $(ls "$DUMP" | wc -l) dumps"
