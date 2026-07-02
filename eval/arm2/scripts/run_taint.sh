#!/bin/bash
# Log4Shell downstream case study: does taint find the source->JNDI-lookup flow
# under each reflection setting? string-constant vs solar vs llm(Reflex) vs null+log(ref).
set -u
cd /home/likaixuan/static_analysis/PTA-enhancement/Tai-e
SC=/tmp/claude-1003/-home-likaixuan-static-analysis-PTA-enhancement/81a5452e-45fd-403c-8e1e-a4576e779421/scratchpad
for mode in string-constant solar llm log; do
  echo "===== taint: $mode ====="
  timeout 1800 ./gradlew run --args="--options-file $SC/taint/options-$mode.yml" \
    -x javadoc --console=plain -q > "$SC/taint/run-$mode.log" 2>&1
  echo "$mode exit $?"
  grep -iE "Detected .* taint flow|TaintFlow|lookup" "$SC/taint/run-$mode.log" | head -8
  echo "---"
done
echo "TAINT DONE"
