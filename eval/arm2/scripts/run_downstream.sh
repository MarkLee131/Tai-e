#!/bin/bash
# A: call-graph completeness + failure-mode, ALL 11 (warm cache, [cg]/[disposer] lines)
# B: cross-model oracle on a 4-benchmark subset
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
save(){ cp build/test-results/reflRecall/*ReflectionRecallEval*.xml "$SC/$1.xml" 2>/dev/null && echo "[saved $1]"; }

echo "===== A: call-graph completeness + failure-mode, all 11 ====="
timeout 5400 ./gradlew reflRecall \
  -PreflBenchmarks=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse \
  -ParmLive -x javadoc --console=plain -q > "$SC/expCG.log" 2>&1
echo "A exit $?"; save expCG

echo "===== B: cross-model oracle (flash-lite) ====="
timeout 3000 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr,fop,pmd -ParmLive \
  -PreflModel=gemini-2.5-flash-lite -PreflCacheSalt=fl -x javadoc --console=plain -q \
  > "$SC/expModel_flashlite.log" 2>&1
echo "B[flash-lite] exit $?"; save expModel_flashlite

echo "===== B: cross-model oracle (2.0-flash) ====="
timeout 3000 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr,fop,pmd -ParmLive \
  -PreflModel=gemini-2.0-flash -PreflCacheSalt=f20 -x javadoc --console=plain -q \
  > "$SC/expModel_20flash.log" 2>&1
echo "B[2.0-flash] exit $?"; save expModel_20flash

echo "DOWNSTREAM+MODEL DONE"
