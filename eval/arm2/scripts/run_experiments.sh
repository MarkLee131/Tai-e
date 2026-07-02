#!/bin/bash
# Sequential experiment battery: D (bootstrap re-verify) -> A (ablation matrix)
# -> B (context levels) -> C (cold-cache cost). Saves each run's XML aside.
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
save() { cp build/test-results/reflRecall/*ReflectionRecallEval*.xml "$SC/$1.xml" 2>/dev/null && echo "[saved $1]"; }

echo "===== D: bootstrap cascade re-verify (no LLM) ====="
timeout 1200 ./gradlew reflRecall -PreflBenchmarks=luindex -PreflDebug \
  -PreflBootstrapLog="$SC/luindex-bootstrap.log" -x javadoc --console=plain -q \
  > "$SC/expD.log" 2>&1; echo "D exit $?"; save expD

echo "===== A: ablation matrix (live, warm cache) ====="
for ab in selfInference subtypeExpansion seeding grounding serviceLoader; do
  echo "--- ablate $ab ---"
  timeout 2400 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr -ParmLive \
    -PreflAblate=$ab -x javadoc --console=plain -q \
    > "$SC/expA_$ab.log" 2>&1; echo "A[$ab] exit $?"; save "expA_$ab"
done

echo "===== B: context levels (live) ====="
for ctx in none id; do
  echo "--- context $ctx ---"
  timeout 3000 ./gradlew reflRecall -PreflBenchmarks=bloat,chart,hsqldb,fop -ParmLive \
    -PreflContext=$ctx -x javadoc --console=plain -q \
    > "$SC/expB_$ctx.log" 2>&1; echo "B[$ctx] exit $?"; save "expB_$ctx"
done

echo "===== C: cold-cache cost (live, salted) ====="
timeout 3000 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr,pmd -ParmLive \
  -PreflCacheSalt=cold1 -x javadoc --console=plain -q \
  > "$SC/expC.log" 2>&1; echo "C exit $?"; save expC

echo "ALL EXPERIMENTS DONE"
