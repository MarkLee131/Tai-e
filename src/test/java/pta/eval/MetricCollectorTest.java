package pta.eval;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link MetricCollector}.
 *
 * <p>Uses the {@code Dispatch} benchmark (basic/Dispatch.java) which
 * exercises virtual dispatch to three targets — ensuring
 * {@code polyCallSites >= 1}, {@code reachableMethods > 0},
 * {@code avgPtsSize > 0}, and {@code objects > 0}.
 *
 * <p>The {@code Cast} benchmark (basic/Cast.java) adds casts to ensure
 * {@code mayFailCasts > 0}.
 */
public class MetricCollectorTest {

    private static final String PTA_ROOT = "src/test/resources/pta";

    /** CSV column count: config, benchmark, timeMs, memMb + 6 metric fields + costUsd + llmQueries. */
    private static final int EXPECTED_CSV_COLUMNS = 12;

    /**
     * Step 1: run CI PTA on Dispatch.java and assert structural sanity of metrics
     * and CSV row format.
     *
     * <p>Dispatch.java has exactly one polymorphic virtual call site (a.foo())
     * dispatching to A.foo(), B.foo() and C.foo().
     */
    @Test
    void collectsReachableMethodsAndAvgPtsSizeAndCsvShape() {
        Tests.testPTA(false, "basic", "Dispatch", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        MetricCollector collector = new MetricCollector();
        MetricCollector.Metrics m = collector.collect(result);

        assertTrue(m.reachableMethods() > 0,
                "reachableMethods must be positive — at least main() is reachable");
        assertTrue(m.avgPtsSize() > 0.0,
                "avgPtsSize must be positive — Dispatch creates multiple objects");
        assertTrue(m.objects() > 0,
                "objects must be positive — Dispatch creates T(1), T(2), T(3)");
        assertTrue(m.polyCallSites() >= 1,
                "Dispatch's a.foo() dispatches to 3 targets → at least 1 poly call site");

        // CSV row shape: pass 0.0 costUsd and 0 llmQueries for a baseline run
        String csv = m.toCsvRow("ci", "Dispatch", 100L, 64L, 0.0, 0L);
        String[] parts = csv.split(",", -1);
        assertEquals(EXPECTED_CSV_COLUMNS, parts.length,
                "CSV row must have exactly " + EXPECTED_CSV_COLUMNS + " columns");

        // Column order: config, benchmark, timeMs, memMb, mayFailCasts, avgPtsSize,
        //               polyCallSites, reachableMethods, aliasPairs, objects, costUsd, llmQueries
        assertEquals("ci", parts[0], "column 0 must be config");
        assertEquals("Dispatch", parts[1], "column 1 must be benchmark");
        assertEquals("100", parts[2], "column 2 must be timeMs");
        assertEquals("64", parts[3], "column 3 must be memMb");
        assertEquals("0.000000", parts[10], "column 10 must be costUsd=0.000000");
        assertEquals("0", parts[11], "column 11 must be llmQueries=0");
    }

    /**
     * Step 2: run CI PTA on Cast.java and assert mayFailCasts > 0.
     *
     * <p>Cast.java contains three cast expressions where the source variable
     * may point to objects that are NOT subtypes of the cast target.
     */
    @Test
    void countsMayFailCastsFromCastBenchmark() {
        Tests.testPTA(false, "basic", "Cast", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        MetricCollector collector = new MetricCollector();
        MetricCollector.Metrics m = collector.collect(result);

        assertTrue(m.mayFailCasts() > 0,
                "Cast.java has cast expressions that may fail → mayFailCasts must be positive");
    }

    /** CSV_HEADER must list 12 column names in the prescribed order. */
    @Test
    void csvHeaderHasCorrectColumnsInOrder() {
        String header = MetricCollector.Metrics.CSV_HEADER;
        String[] cols = header.split(",", -1);
        assertEquals(EXPECTED_CSV_COLUMNS, cols.length,
                "CSV_HEADER must have exactly " + EXPECTED_CSV_COLUMNS + " columns");
        assertEquals("config", cols[0]);
        assertEquals("benchmark", cols[1]);
        assertEquals("timeMs", cols[2]);
        assertEquals("memMb", cols[3]);
        assertEquals("mayFailCasts", cols[4]);
        assertEquals("avgPtsSize", cols[5]);
        assertEquals("polyCallSites", cols[6]);
        assertEquals("reachableMethods", cols[7]);
        assertEquals("aliasPairs", cols[8]);
        assertEquals("objects", cols[9]);
        assertEquals("costUsd", cols[10]);
        assertEquals("llmQueries", cols[11]);
    }
}
