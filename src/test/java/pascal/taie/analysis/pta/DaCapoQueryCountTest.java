/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pta.llm.LlmOracle;
import pta.llm.LlmQuery;
import pta.llm.LlmResponse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Zero-cost empirical measurement of LLM query counts per arm on DaCapo-2006.
 *
 * <p>Design: each arm's static oracle field is set to a {@link MeasuringOracle}
 * before calling {@code Main.main()}. The oracle counts every {@code ask()} call
 * and accumulates the prompt character length so we can estimate avg input tokens
 * (tokens ≈ chars / 4).
 *
 * <p>Oracle default answers mirror a "all-NO" oracle, which is the UPPER BOUND on
 * arm① queries (≤ 2 000) and arm③ queries (≤ 256) because:
 * <ul>
 *   <li>arm①: with all-NO the selection counter never reaches its cap (100), so
 *       the loop traverses all candidates up to MAX_QUERIES=2000.</li>
 *   <li>arm③: with all-NO, proposed set is empty; loop still runs all candidates
 *       up to MAX_QUERIES=256.</li>
 *   <li>arm②: query count = number of unresolved Class.newInstance() sites,
 *       independent of YES/NO.</li>
 * </ul>
 * A real oracle (≈15% YES rate) would stop arm① after ≈100/0.15 ≈ 667 queries.
 */
public class DaCapoQueryCountTest {

    private static final String BENCHMARK_HOME = "java-benchmarks";
    /** Guard: each arm run may not exceed this wall-clock time (seconds). */
    private static final int TIME_LIMIT_SEC = 600;

    /** Gemini 2.5 Flash-Lite input rate: $0.10 / 1M tokens. */
    private static final double IN_RATE = 1e-7;
    /** Gemini 2.5 Flash-Lite output rate: $0.40 / 1M tokens. */
    private static final double OUT_RATE = 4e-7;

    // -----------------------------------------------------------------------
    // Inner oracle: counts calls, accumulates prompt char length
    // -----------------------------------------------------------------------

    static class MeasuringOracle implements LlmOracle {
        private final String defaultAnswer;
        long queries = 0;
        long totalPromptChars = 0;

        MeasuringOracle(String defaultAnswer) {
            this.defaultAnswer = defaultAnswer;
        }

        @Override
        public LlmResponse ask(LlmQuery q) {
            queries++;
            totalPromptChars += q.prompt().length();
            return new LlmResponse(defaultAnswer, true, 0.0);
        }

        void reset() {
            queries = 0;
            totalPromptChars = 0;
        }

        long avgPromptChars() {
            return queries > 0 ? totalPromptChars / queries : 0;
        }
    }

    // -----------------------------------------------------------------------
    // Main test entry
    // -----------------------------------------------------------------------

    @Test
    void measureDaCapo() {
        Assumptions.assumeTrue(
                Boolean.getBoolean("dacapo.measure"),
                "DaCapo measurement disabled — set -Ddacapo.measure=true to enable");

        Map<String, BenchmarkInfo> allBenchmarks =
                BenchmarkInfo.load(BENCHMARK_HOME + "/benchmark-info.yml");

        String listProp = System.getProperty(
                "dacapo.benchmarks",
                "luindex,antlr,bloat,fop,hsqldb,lusearch,pmd,xalan,chart");
        List<String> toMeasure = Arrays.asList(listProp.split(","));

        MeasuringOracle arm1Oracle = new MeasuringOracle("NO");
        MeasuringOracle arm2Oracle = new MeasuringOracle("");
        MeasuringOracle arm3Oracle = new MeasuringOracle("NO");

        // Columns: program, a1q, a1avgC, a2q, a2avgC, a3q, a3avgC
        List<String[]> rows = new ArrayList<>();

        for (String benchId : toMeasure) {
            benchId = benchId.trim();
            BenchmarkInfo info = allBenchmarks.get(benchId);
            if (info == null) {
                System.out.printf("[WARN] benchmark not found in yml: %s%n", benchId);
                rows.add(row(benchId, "N/A", "N/A", "N/A", "N/A", "N/A", "N/A"));
                continue;
            }

            String appCp  = buildClassPath(info.apps());
            String libCp  = buildClassPath(info.libs());
            String reflLog = new File(BENCHMARK_HOME, info.reflectionLog()).toString();

            System.out.printf("%n=== Measuring %s (jdk=%d, main=%s) ===%n",
                    benchId, info.jdk(), info.main());

            // ── Arm ① ──────────────────────────────────────────────────────
            arm1Oracle.reset();
            pta.arm1.ArmOracleFactory.setOracle(arm1Oracle);
            boolean a1ok = false;
            try {
                Main.main(buildArgs(info.jdk(), appCp, libCp, info.main(), reflLog,
                        "2-obj", "llm", "null", ""));
                a1ok = true;
                System.out.printf("  arm1: %d queries, avg prompt %d chars%n",
                        arm1Oracle.queries, arm1Oracle.avgPromptChars());
            } catch (Throwable t) {
                System.out.printf("  arm1 FAILED: %s: %s%n",
                        t.getClass().getSimpleName(), t.getMessage());
            } finally {
                pta.arm1.ArmOracleFactory.clearOracle();
            }

            // ── Arm ② ──────────────────────────────────────────────────────
            arm2Oracle.reset();
            pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel.setOracle(arm2Oracle);
            boolean a2ok = false;
            try {
                Main.main(buildArgs(info.jdk(), appCp, libCp, info.main(), reflLog,
                        "ci", "null", "llm", ""));
                a2ok = true;
                System.out.printf("  arm2: %d queries, avg prompt %d chars%n",
                        arm2Oracle.queries, arm2Oracle.avgPromptChars());
            } catch (Throwable t) {
                System.out.printf("  arm2 FAILED: %s: %s%n",
                        t.getClass().getSimpleName(), t.getMessage());
            } finally {
                pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel.clearOracle();
            }

            // ── Arm ③ ──────────────────────────────────────────────────────
            arm3Oracle.reset();
            pta.arm3.ArmOracleFactory.setOracle(arm3Oracle);
            boolean a3ok = false;
            try {
                Main.main(buildArgs(info.jdk(), appCp, libCp, info.main(), reflLog,
                        "ci", "llm-cafd", "null", ""));
                a3ok = true;
                System.out.printf("  arm3: %d queries, avg prompt %d chars%n",
                        arm3Oracle.queries, arm3Oracle.avgPromptChars());
            } catch (Throwable t) {
                System.out.printf("  arm3 FAILED: %s: %s%n",
                        t.getClass().getSimpleName(), t.getMessage());
            } finally {
                pta.arm3.ArmOracleFactory.clearOracle();
            }

            rows.add(row(benchId,
                    a1ok ? String.valueOf(arm1Oracle.queries) : "ERR",
                    a1ok ? String.valueOf(arm1Oracle.avgPromptChars()) : "ERR",
                    a2ok ? String.valueOf(arm2Oracle.queries) : "ERR",
                    a2ok ? String.valueOf(arm2Oracle.avgPromptChars()) : "ERR",
                    a3ok ? String.valueOf(arm3Oracle.queries) : "ERR",
                    a3ok ? String.valueOf(arm3Oracle.avgPromptChars()) : "ERR"));
        }

        printReport(rows);
    }

    // -----------------------------------------------------------------------
    // Arg building
    // -----------------------------------------------------------------------

    private static String[] buildArgs(int jdk, String appCp, String libCp,
                                      String main, String reflLog,
                                      String cs, String advanced,
                                      String reflInference, String plugins) {
        List<String> args = new ArrayList<>();
        args.addAll(Arrays.asList("-java", String.valueOf(jdk)));
        args.addAll(Arrays.asList("-acp", appCp));
        if (libCp != null && !libCp.isEmpty()) {
            args.addAll(Arrays.asList("-cp", libCp));
        }
        args.addAll(Arrays.asList("-m", main));

        StringBuilder pta = new StringBuilder();
        pta.append("distinguish-string-constants:null")
           .append(";merge-string-objects:false")
           .append(";cs:").append(cs)
           .append(";advanced:").append(advanced)
           .append(";reflection-inference:").append(reflInference)
           .append(";reflection-log:").append(reflLog)
           .append(";time-limit:").append(TIME_LIMIT_SEC);
        if (plugins != null && !plugins.isEmpty()) {
            pta.append(";plugins:[").append(plugins).append("]");
        }
        args.addAll(Arrays.asList("-a", "pta=" + pta));
        return args.toArray(new String[0]);
    }

    private static String buildClassPath(List<String> paths) {
        List<String> resolved = new ArrayList<>();
        for (String p : paths) {
            File f = new File(BENCHMARK_HOME, p);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                resolved.add(f.getPath());
            } else if (f.isDirectory()) {
                resolved.add(f.getPath());
                File[] children = f.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isFile() && child.getName().endsWith(".jar")) {
                            resolved.add(child.getPath());
                        }
                    }
                }
            }
        }
        return String.join(File.pathSeparator, resolved);
    }

    // -----------------------------------------------------------------------
    // Report printing
    // -----------------------------------------------------------------------

    private static void printReport(List<String[]> rows) {
        String sep = "=".repeat(108);
        String div = "-".repeat(108);
        System.out.println();
        System.out.println(sep);
        System.out.println("DaCapo-2006 LLM Query-Count Measurement  (MOCK mode, $0 cost)");
        System.out.println("Oracle: all-NO  →  upper-bound on arm① (cap 2000) and arm③ (cap 256)");
        System.out.println(sep);
        System.out.printf("%-12s | %8s %9s | %8s %9s | %8s %9s%n",
                "Program", "A1_q", "A1_avgC", "A2_q", "A2_avgC", "A3_q", "A3_avgC");
        System.out.println(div);

        long sumA1Q = 0, sumA1Chars = 0;
        long sumA2Q = 0, sumA2Chars = 0;
        long sumA3Q = 0, sumA3Chars = 0;
        int counted = 0;

        for (String[] r : rows) {
            System.out.printf("%-12s | %8s %9s | %8s %9s | %8s %9s%n",
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6]);
            try {
                long a1q = Long.parseLong(r[1]);
                long a1c = Long.parseLong(r[2]);
                long a2q = Long.parseLong(r[3]);
                long a2c = Long.parseLong(r[4]);
                long a3q = Long.parseLong(r[5]);
                long a3c = Long.parseLong(r[6]);
                sumA1Q += a1q;
                sumA1Chars += a1q * a1c;
                sumA2Q += a2q;
                sumA2Chars += a2q * a2c;
                sumA3Q += a3q;
                sumA3Chars += a3q * a3c;
                counted++;
            } catch (NumberFormatException ignored) {
                // ERR or N/A row
            }
        }

        System.out.println(div);
        System.out.printf("%-12s | %8d %9s | %8d %9s | %8d %9s   (%d programs)%n",
                "TOTAL", sumA1Q, "-", sumA2Q, "-", sumA3Q, "-", counted);
        System.out.println(sep);

        // Cost computation (Flash-Lite: $0.10/M in, $0.40/M out)
        double arm1AvgIn  = sumA1Q > 0 ? (double) sumA1Chars / sumA1Q / 4.0 : 0;
        double arm2AvgIn  = sumA2Q > 0 ? (double) sumA2Chars / sumA2Q / 4.0 : 50.0; // ~200 chars
        double arm3AvgIn  = sumA3Q > 0 ? (double) sumA3Chars / sumA3Q / 4.0 : 0;

        // Output tokens: arm1/arm3 = YES/NO ≈ 2 tok; arm2 = class name ≈ 5 tok
        double arm1OutTok = 2.0, arm2OutTok = 5.0, arm3OutTok = 2.0;

        double arm1Cost = sumA1Q * (arm1AvgIn * IN_RATE + arm1OutTok * OUT_RATE);
        double arm2Cost = sumA2Q * (arm2AvgIn * IN_RATE + arm2OutTok * OUT_RATE);
        double arm3Cost = sumA3Q * (arm3AvgIn * IN_RATE + arm3OutTok * OUT_RATE);

        System.out.println("Cost estimate — Gemini 2.5 Flash-Lite ($0.10/M in, $0.40/M out):");
        System.out.printf("  Arm①: %6d queries × ~%.0f avg-in-tok + 2 out-tok  = $%.6f%n",
                sumA1Q, arm1AvgIn, arm1Cost);
        System.out.printf("  Arm②: %6d queries × ~%.0f avg-in-tok + 5 out-tok  = $%.6f%n",
                sumA2Q, arm2AvgIn, arm2Cost);
        System.out.printf("  Arm③: %6d queries × ~%.0f avg-in-tok + 2 out-tok  = $%.6f%n",
                sumA3Q, arm3AvgIn, arm3Cost);
        System.out.printf("  TOTAL (all 3 arms, %d programs): $%.6f%n",
                counted, arm1Cost + arm2Cost + arm3Cost);
        System.out.println(sep);
        System.out.println("NOTE: arm① upper-bound assumes all-NO oracle (real oracle with");
        System.out.println("  ~15% YES rate → ~667 queries/program until 100 are selected).");
    }

    private static String[] row(String p,
                                 String a1q, String a1c,
                                 String a2q, String a2c,
                                 String a3q, String a3c) {
        return new String[]{p, a1q, a1c, a2q, a2c, a3q, a3c};
    }
}
