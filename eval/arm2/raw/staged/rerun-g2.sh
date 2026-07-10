#!/usr/bin/env bash
# Re-eval after the G2 use-site-cast grounding fix (b9bd1c4d). The prompt changed
# at downcast-newInstance sites, so RQ1/RQ7 + clean dumps must be regenerated.
# Mostly warm cache (only grounded sites re-query); FLOOR-guarded.
set -uo pipefail
cd "$(dirname "$0")/../../../.."
OUT=eval/arm2/raw/staged-g2; mkdir -p "$OUT"
XML=build/test-results/reflRecall/TEST-pascal.taie.analysis.pta.ReflectionRecallEval.xml
DUMP=eval/arm2/raw/reachdumps
G="-x javadoc --console=plain -q"
save(){ cp "$XML" "$OUT/$1.xml" && echo "[saved $1]"; }
ALL=luindex,antlr,bloat,lusearch,chart,hsqldb,fop,jython,xalan,pmd,eclipse
echo "=== RQ1 (11 DaCapo) + fresh clean dumps"
timeout 7200 ./gradlew reflRecall -PreflBenchmarks=$ALL -ParmLive -PreflDumpReach=$DUMP -PreflDumpTag=g2clean $G && save rq1_g2 || echo "[FAIL rq1]"
echo "=== RQ7 (7 apps)"
timeout 7200 ./gradlew reflRecall -PreflBenchmarks=findbugs-3.0,columba-1.4,jedit-3.0,freecol-0.10.3,gruntspud-0.4.6,briss-0.9,soot-2.3.0 -ParmLive $G && save rw_g2 || echo "[FAIL rw]"
echo "G2 RE-EVAL DONE"
