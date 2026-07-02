#!/bin/bash
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
save(){ cp build/test-results/reflRecall/*ReflectionRecallEval*.xml "$SC/$1.xml" 2>/dev/null && echo "[saved $1]"; }

echo "===== Log4Shell taint under composed llm mode ====="
timeout 1800 ./gradlew run --args="--options-file $SC/taint/options-llm.yml" \
  -x javadoc --console=plain -q > "$SC/taint/run-llm-composed.log" 2>&1
echo "compose-llm exit $?"
grep -oE "Detected [0-9]+ taint flow" "$SC/taint/run-llm-composed.log" | head -1
grep -iE "JndiManager|InitialContext" "$SC/taint/run-llm-composed.log" | grep -i "TaintFlow" | head -2

echo "===== DaCapo recall/precision under composed llm (must not regress) ====="
timeout 5400 ./gradlew reflRecall \
  -PreflBenchmarks=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse \
  -ParmLive -x javadoc --console=plain -q > "$SC/expCompose.log" 2>&1
echo "recall exit $?"; save expCompose
echo "COMPOSE DONE"
