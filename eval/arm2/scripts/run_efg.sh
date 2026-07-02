#!/bin/bash
# E (stability across fresh-oracle runs) -> F (2-obj spot check) -> G (per-site dump, all 11)
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
save() { cp build/test-results/reflRecall/*ReflectionRecallEval*.xml "$SC/$1.xml" 2>/dev/null && echo "[saved $1]"; }

echo "===== E: stability — 3 fresh-cache reseeded runs (plus expC/cold1 = 4 samples) ====="
for salt in stab2 stab3 stab4; do
  echo "--- salt $salt ---"
  timeout 3000 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr,pmd -ParmLive \
    -PreflCacheSalt=$salt -x javadoc --console=plain -q \
    > "$SC/expE_$salt.log" 2>&1; echo "E[$salt] exit $?"; save "expE_$salt"
done

echo "===== F: 2-obj context-sensitivity spot check ====="
timeout 4800 ./gradlew reflRecall -PreflBenchmarks=luindex,antlr,hsqldb -ParmLive \
  -PreflCs=2-obj -x javadoc --console=plain -q \
  > "$SC/expF.log" 2>&1; echo "F exit $?"; save expF

echo "===== G: per-site target dump, all 11 benchmarks ====="
timeout 5400 ./gradlew reflRecall \
  -PreflBenchmarks=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse \
  -ParmLive -PreflDumpTargets -x javadoc --console=plain -q \
  > "$SC/expG.log" 2>&1; echo "G exit $?"; save expG

echo "ALL EFG DONE"
