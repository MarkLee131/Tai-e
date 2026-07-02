#!/bin/bash
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
echo "===== real-world app suite (Elf/SOLAR benchmarks, live) ====="
timeout 7200 ./gradlew reflRecall \
  -PreflBenchmarks=findbugs-3.0,columba-1.4,jedit-3.0,freecol-0.10.3,gruntspud-0.4.6,briss-0.9,soot-2.3.0 \
  -ParmLive -x javadoc --console=plain -q > "$SC/expRealworld.log" 2>&1
echo "exit $?"
cp build/test-results/reflRecall/*ReflectionRecallEval*.xml "$SC/expRealworld.xml" 2>/dev/null && echo saved
echo "REALWORLD DONE"
