#!/bin/bash
# Cross-family oracle sweep (single-vendor robustness for the precision story):
# same prompt + same Phi/fence disposer, only the proposer's vendor changes.
# Families: openai (gpt-4o-mini), deepseek (deepseek-chat). Benchmarks: the 6
# DaCapo incidents with TamiFlex ground truth used in tab:rq1/tab:gsel.
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
OUT=eval/arm2/raw/staged-g2/crossfamily
mkdir -p "$OUT"
BENCHES="luindex antlr hsqldb pmd chart fop"
save() { cp build/test-results/reflRecall/TEST-*ReflectionRecallEval*.xml "$OUT/$1.xml" 2>/dev/null && echo "[saved $1]"; }

for fam in openai deepseek; do
  for b in $BENCHES; do
    echo "===== $fam :: $b ====="
    timeout 2400 ./gradlew reflRecall -PreflBenchmarks=$b -ParmLive -PreflOracle=$fam \
      -x javadoc --console=plain -q > "$OUT/log_${fam}_${b}.log" 2>&1
    echo "$fam/$b exit $?"
    save "xfam_${fam}_${b}"
  done
done
echo "CROSSFAMILY SWEEP DONE"
