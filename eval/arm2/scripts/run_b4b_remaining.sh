#!/usr/bin/env bash
# B4b remaining battery (relaunch after the session-limit casualty killed the
# original driver mid-run): RQ2 tiers, RQ6 cross-model, RQ7 realworld, compose.
# Staged pipeline (default). XMLs isolated in raw/staged/.
set -uo pipefail
cd "$(dirname "$0")/../../.."
OUT=eval/arm2/raw/staged
XML=build/test-results/reflRecall/TEST-pascal.taie.analysis.pta.ReflectionRecallEval.xml
G="-x javadoc --console=plain -q"
save(){ cp "$XML" "$OUT/$1.xml" && echo "[saved $1]"; }
run(){ local name=$1; shift; echo "=== $name: $*"; timeout 7200 ./gradlew reflRecall "$@" $G && save "$name" || echo "[FAILED $name exit $?]"; }

run expB_none    -PreflBenchmarks=bloat,chart,hsqldb,fop -ParmLive -PreflContext=none
run expB_id      -PreflBenchmarks=bloat,chart,hsqldb,fop -ParmLive -PreflContext=id
run expModel_flashlite -PreflBenchmarks=luindex,antlr -ParmLive -PreflModel=gemini-2.5-flash-lite
run expModel_20flash   -PreflBenchmarks=luindex,antlr -ParmLive -PreflModel=gemini-2.0-flash
run expRealworld -PreflBenchmarks=findbugs-3.0,columba-1.4,jedit-3.0,freecol-0.10.3,gruntspud-0.4.6,briss-0.9,soot-2.3.0 -ParmLive
run expCompose   -PreflBenchmarks=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse -ParmLive -PreflCompose
echo "B4B REMAINING BATTERY DONE: $(ls $OUT/*.xml | wc -l) XMLs total"
