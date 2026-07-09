#!/usr/bin/env bash
# Per-site blast radius (RQ-adv / prop:gating): solo and solo-malicious runs.
#
# For each benchmark (luindex, antlr — the two verified schedule-STABLE
# benchmarks; fop has schedule-dependent clean-reach jitter, 2026-07-10):
#   0. enumeration : one llm-only clean run; the queried sites and their clean
#                    answers are extracted from the JUnit XML query lines
#                    "[arm2-llm] llm-<kind> at <siteId> → [answers]" and written
#                    to eval/arm2/raw/sites-luindex-antlr.txt (bench\tsite\tanswer).
#   1. allsilent   : silent rate=1.0 — the floor Reach_allsilent (no oracle help).
#   2. solo-<k>    : silent rate=1.0 EXCEPT site k (arm2.corruptExceptSite) —
#                    site k is the only one answered. S(ℓ_k) = |solo_k − allsilent|.
#   3. mal-<k>     : fixed:<WRONG> rate=1.0 ONLY at site k (arm2.corruptSite) —
#                    site k answers a loadable-but-wrong class, everything else
#                    answers normally. BR(ℓ_k) = |mal_k − ∪ clean draws|.
#
# Site filter substrings: arm2.corruptSite / arm2.corruptExceptSite match by
# SUBSTRING of the query contextId "<pkg.Class: subsig>@idx". We use
# "SimpleClassName: subsig>@idx" (declaring-class package stripped) — short,
# and pairwise-unique across each benchmark's sites (verified below; the
# @idx suffix disambiguates same-subsig sites like the two lucene <clinit>s).
#
# Wrong-loadable class per benchmark (recorded in blastradius.csv; must stay in
# sync with WRONG in blastradius_report.py):
#   luindex → dacapo.lusearch.LusearchHarness : ANOTHER benchmark's harness that
#             ships inside luindex.jar; extends dacapo.Benchmark, so it is both
#             loadable and Benchmark-fence-passing (the strongest in-cone
#             adversary), yet appears in neither luindex-refl.log nor any clean
#             reach draw.
#   antlr   → antlr.debug.ParserEventSupport : loadable application class in
#             antlr.jar (no foreign harness ships there); not in antlr-refl.log,
#             not a CodeGenerator (out of every site's fence cone), and the whole
#             antlr.debug package is absent from all clean reach draws.
#
# Every run is warm-cache: corruption applies AFTER the (cached) delegate
# answers, so [oracle] must report live=0 cost=$0.0000. The script HARD-STOPS
# on live>0, cost>$0.01, a solo run answering any unintended site, or the
# allsilent floor differing from the archived silent-1.00-s1 dump (that would
# be run-to-run instability — a reportable finding, not something to average).
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> Tai-e/
BENCHES=(luindex antlr)
declare -A WRONG=(
  [luindex]=dacapo.lusearch.LusearchHarness
  [antlr]=antlr.debug.ParserEventSupport
)
DUMP=eval/arm2/raw/reachdumps
SITES=eval/arm2/raw/sites-luindex-antlr.txt
XML=build/test-results/reflRecall/TEST-pascal.taie.analysis.pta.ReflectionRecallEval.xml
LOG=eval/arm2/raw/blastradius-runs.log
GFLAGS=(-x javadoc --console=plain)

: > "$LOG"

# Query lines from the JUnit XML (system-out is CDATA, so no XML unescaping
# needed): "arm2-llm] llm-<kind> at <siteId> → [answers]".
qlines() { grep -oE "arm2-llm\] llm-[a-z]+ at [^&]*" "$XML" || true; }

# Oracle guard: [oracle] queries=N live=M cost=$C — warm cache means M=0, C≈0.
check_oracle() {  # label
  local line live cost
  line=$(grep -o "\[oracle\][^&]*" "$XML" | head -1)
  echo "  $line" | tee -a "$LOG"
  live=$(sed 's/.*live=\([0-9]*\).*/\1/' <<<"$line")
  cost=$(sed 's/.*cost=\$\([0-9.]*\).*/\1/' <<<"$line")
  if [[ "$live" != 0 ]] || awk "BEGIN{exit !($cost > 0.01)}"; then
    echo "FATAL($1): non-warm-cache run (live=$live cost=\$$cost) — stopping" | tee -a "$LOG"
    exit 1
  fi
}

run() {  # bench tag extra-gradle-args...
  local bench=$1 tag=$2; shift 2
  echo "=== $bench $tag ===" | tee -a "$LOG"
  if ! ./gradlew reflRecall -PreflBenchmarks="$bench" -ParmLive -PreflConfigs=llm \
      -PreflDumpReach="$DUMP" -PreflDumpTag="$tag" "$@" "${GFLAGS[@]}" \
      > eval/arm2/raw/blastradius-gradle.log 2>&1; then
    echo "FATAL($bench/$tag): gradle run failed" | tee -a "$LOG"
    tail -30 eval/arm2/raw/blastradius-gradle.log
    exit 1
  fi
  qlines | sed 's/^/  /' | tee -a "$LOG"
  check_oracle "$bench/$tag"
}

: > "$SITES"
for bench in "${BENCHES[@]}"; do
  # --- 0. enumeration: llm-only clean run; no dump tag that could shadow the
  #        archived clean draws (we re-dump under tag "enum-check" only to make
  #        sure this JVM's clean attractor equals the archived one).
  run "$bench" enum-check
  # Greedy (<.*>@N) is required: subsignatures like "void <clinit>()" put a '>'
  # inside the siteId; the " → [" separator occurs exactly once per line.
  qlines | sed -E 's/^arm2-llm\] llm-[a-z]+ at (<.*>@[0-9]+) → \[(.*)\]$/\1\t\2/' \
    | awk -F'\t' '!seen[$1]++' \
    | while IFS=$'\t' read -r site answer; do
        printf '%s\t%s\t%s\n' "$bench" "$site" "$answer" >> "$SITES"
      done
  if ! cmp -s "$DUMP/$bench-llm-enum-check.txt" "$DUMP/$bench-llm-clean.txt"; then
    echo "FATAL($bench): clean reach differs from archived $bench-llm-clean.txt —" \
         "schedule instability on a supposedly-stable benchmark; stopping" | tee -a "$LOG"
    exit 1
  fi
  rm -f "$DUMP/$bench-llm-enum-check.txt"

  # Per-bench site list + filter substrings, pairwise-uniqueness check.
  mapfile -t sites < <(awk -F'\t' -v b="$bench" '$1==b{print $2}' "$SITES")
  echo "  sites($bench): ${#sites[@]}" | tee -a "$LOG"
  subs=()
  for site in "${sites[@]}"; do
    inner="${site#<}"; cls="${inner%%:*}"; rest="${inner#*: }"
    subs+=("${cls##*.}: $rest")
  done
  for i in "${!sites[@]}"; do
    for j in "${!sites[@]}"; do
      if [[ $i != "$j" && "${sites[$j]}" == *"${subs[$i]}"* ]]; then
        echo "FATAL($bench): filter substring '${subs[$i]}' also matches ${sites[$j]}" \
          | tee -a "$LOG"
        exit 1
      fi
    done
  done

  # --- 1. all-silent floor.
  run "$bench" allsilent -PreflCorrupt=silent -PreflCorruptRate=1.0
  if qlines | grep -v "→ \[\]$" | grep -q .; then
    echo "FATAL($bench/allsilent): some site still answered" | tee -a "$LOG"; exit 1
  fi
  if [[ -f "$DUMP/$bench-llm-silent-1.00-s1.txt" ]] \
     && ! cmp -s "$DUMP/$bench-llm-allsilent.txt" "$DUMP/$bench-llm-silent-1.00-s1.txt"; then
    echo "FATAL($bench): allsilent floor differs from archived silent-1.00-s1 dump —" \
         "run-to-run instability; stopping (report, do not average)" | tee -a "$LOG"
    exit 1
  fi

  # --- 2./3. per-site solo + malicious runs.
  for i in "${!sites[@]}"; do
    k=$((i + 1)); site="${sites[$i]}"; sub="${subs[$i]}"
    echo "  site $k filter substring: '$sub'" | tee -a "$LOG"

    run "$bench" "solo-$k" -PreflCorrupt=silent -PreflCorruptRate=1.0 \
      "-PreflCorruptExceptSite=$sub"
    # Exactly the intended site may answer: every other query line must be [].
    if qlines | grep -vF "at $site →" | grep -v "→ \[\]$" | grep -q .; then
      echo "FATAL($bench/solo-$k): a site other than $site answered" | tee -a "$LOG"
      exit 1
    fi
    if ! qlines | grep -F "at $site →" | grep -v "→ \[\]$" | grep -q .; then
      echo "  note($bench/solo-$k): site $k never fired/answered (gated by a" \
           "silenced upstream site) — S(site $k) measures 0 by construction" | tee -a "$LOG"
    fi

    run "$bench" "mal-$k" "-PreflCorrupt=fixed:${WRONG[$bench]}" \
      -PreflCorruptRate=1.0 "-PreflCorruptSite=$sub"
    # Only site k's answer is replaced by the wrong class; if it fired, verify.
    if qlines | grep -F "at $site →" | grep -q . \
       && ! qlines | grep -F "at $site → [${WRONG[$bench]}]" | grep -q .; then
      echo "FATAL($bench/mal-$k): site $k fired but did not answer the wrong" \
           "class ${WRONG[$bench]}" | tee -a "$LOG"
      exit 1
    fi
  done
done

echo "blast-radius runs done; sites file: $SITES" | tee -a "$LOG"
