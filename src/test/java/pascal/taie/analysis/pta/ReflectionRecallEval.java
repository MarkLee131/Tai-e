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
import pascal.taie.World;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pascal.taie.util.collection.Sets;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quantitative reflection-recall evaluation for arm② (gated; run with
 * {@code ./gradlew test -PreflRecall --tests pascal.taie.analysis.pta.ReflectionRecallEval}).
 *
 * <h2>Ground truth</h2>
 * Tai-e's DaCapo benchmarks ship TamiFlex-style reflection logs (e.g.
 * {@code luindex-refl.log}). Loaded via {@code reflection-log}, they give the
 * dynamically-observed reflective targets. We define the <b>reflection-reachable</b>
 * ground-truth set GT = reachable(reflection-log) \ reachable(no-reflection):
 * methods reachable ONLY because of logged reflection.
 *
 * <h2>Metric</h2>
 * For each static config C, recall(C) = |reachable(C) ∩ GT| / |GT| — the fraction
 * of dynamically-confirmed reflection-reachable methods that C recovers WITHOUT
 * the log. We compare {@code reflection-inference:string-constant} (baseline) vs
 * {@code reflection-inference:llm} (arm②). The LLM run is live (real Gemini) and
 * only enabled with {@code -ParmLive}.
 */
public class ReflectionRecallEval {

    private static final String HOME = "java-benchmarks";
    private static final int TIME_LIMIT = 600;

    @Test
    void measureReflectionRecall() {
        Assumptions.assumeTrue(Boolean.getBoolean("refl.recall"),
                "reflection-recall eval disabled — run with -PreflRecall");
        boolean live = Boolean.getBoolean("arm2.live");

        var all = BenchmarkInfo.load(HOME + "/benchmark-info.yml");
        String list = System.getProperty("refl.benchmarks", "luindex,antlr");

        System.out.println("\n==== Reflection-recall (GT = log-reachable \\ no-reflection) ====");
        System.out.printf("%-10s | %8s | %10s | %-26s%n",
                "bench", "GT", "sc-recall", live ? "llm: recall/precision/extra" : "llm(skip)");
        for (String id : list.split(",")) {
            id = id.trim();
            BenchmarkInfo info = all.get(id);
            if (info == null) {
                System.out.printf("%-10s | (not in yml)%n", id);
                continue;
            }
            String appCp = cp(info.apps());
            String libCp = cp(info.libs());
            String refl = new File(HOME, info.reflectionLog()).toString();
            try {
                Set<String> none = reach(info, appCp, libCp, "null", null);
                Set<String> log = reach(info, appCp, libCp, "null", refl);
                Set<String> gt = minus(log, none); // reflection-reachable ground truth
                Set<String> sc = reach(info, appCp, libCp, "string-constant", null);
                double rSc = recall(sc, gt);
                if (Boolean.getBoolean("refl.debug")) {
                    debugBreakdown(id, none, log, gt, sc);
                    String bootLog = System.getProperty("refl.bootstrapLog");
                    if (bootLog != null && new File(bootLog).isFile()) {
                        // string-constant + ONLY the findClass→Harness bootstrap (no lucene
                        // log lines): does SelfInferenceModel cascade the lucene subtree in?
                        Set<String> boot = reach(info, appCp, libCp, "string-constant", bootLog);
                        System.out.printf("     [bootstrap] lucene reachable: string-const=%d  "
                                        + "+bootstrap=%d  (GT lucene=%d)  recall +bootstrap=%.3f%n",
                                count(sc, "lucene"), count(boot, "lucene"), count(gt, "lucene"),
                                recall(boot, gt));
                    }
                }
                String llmCol = "—";
                if (live) {
                    LlmInferenceModel.clearOracle(); // live ApiKeyResolver path
                    // Feed the application identity (which we know) so the LLM can
                    // propose convention-driven names (e.g. the benchmark harness class).
                    System.setProperty("arm2.appContext",
                            "This is the DaCapo-2006 benchmark suite; the program under "
                                    + "analysis is the '" + id + "' benchmark. DaCapo launches "
                                    + "each benchmark through a wrapper class named "
                                    + "dacapo.<id>.<CapitalizedId>Harness (e.g. the '" + id
                                    + "' benchmark's harness is dacapo." + id + "."
                                    + Character.toUpperCase(id.charAt(0)) + id.substring(1)
                                    + "Harness).");
                    System.setProperty("arm2.classHint", id);
                    Set<String> llm;
                    try {
                        // arm2.extraLog: supply extra reflective targets alongside the LLM
                        // (used to confirm whether a residual is name-resolution vs a
                        // Class-object-flow gap that the log — not the LLM — fills).
                        String extraLog = System.getProperty("arm2.extraLog");
                        llm = reach(info, appCp, libCp, "llm",
                                (extraLog != null && new File(extraLog).isFile()) ? extraLog : null);
                    } finally {
                        System.clearProperty("arm2.appContext");
                        System.clearProperty("arm2.classHint");
                    }
                    // recall = |llm ∩ GT| / |GT|; precision = fraction of arm②'s
                    // reflection-added methods that are dynamically-confirmed (in GT);
                    // extra = methods llm reaches beyond the log run (over-approximation).
                    Set<String> reflAdded = minus(llm, none);
                    int hit = countRecovered(llm, gt);
                    double prec = reflAdded.isEmpty() ? 1.0 : (double) hit / reflAdded.size();
                    int extra = minus(llm, log).size();
                    llmCol = String.format("r=%.3f p=%.3f +%d", recall(llm, gt), prec, extra);
                    String probes = System.getProperty("refl.probe");
                    if (probes != null) {
                        for (String p : probes.split(",")) {
                            System.out.printf("     [probe] %-32s llm=%d  log=%d%n",
                                    p, count(llm, p), count(log, p));
                        }
                    }
                }
                System.out.printf("%-10s | %8d | %10s | %-26s%n",
                        id, gt.size(), String.format("%.3f", rSc), llmCol);
            } catch (Throwable t) {
                System.out.printf("%-10s | FAILED: %s: %s%n",
                        id, t.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    private static Set<String> reach(BenchmarkInfo info, String appCp, String libCp,
                                     String reflInference, String reflLog) {
        List<String> args = new ArrayList<>(Arrays.asList(
                "-java", String.valueOf(info.jdk()), "-acp", appCp));
        if (libCp != null && !libCp.isEmpty()) {
            args.addAll(Arrays.asList("-cp", libCp));
        }
        args.addAll(Arrays.asList("-m", info.main()));
        StringBuilder pta = new StringBuilder()
                .append("distinguish-string-constants:reflection;merge-string-objects:false")
                .append(";only-app:true;cs:ci")
                .append(";reflection-inference:").append(reflInference)
                .append(";time-limit:").append(TIME_LIMIT);
        if (reflLog != null) {
            pta.append(";reflection-log:").append(reflLog);
        }
        args.addAll(Arrays.asList("-a", "pta=" + pta));
        Main.main(args.toArray(new String[0]));
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getSignature()).collect(Collectors.toSet());
    }

    /** Evidence: package histogram of GT + probe-class reachability per config. */
    private static void debugBreakdown(String id, Set<String> none, Set<String> log,
                                       Set<String> gt, Set<String> sc) {
        System.out.println("---- [debug] " + id + " GT package histogram (top 8) ----");
        java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
        for (String sig : gt) {
            // sig like <org.apache.lucene.index.SegmentReader: ...>
            int lt = sig.indexOf('<');
            int dot = sig.lastIndexOf('.', sig.indexOf(':'));
            String pkg = (lt >= 0 && dot > lt) ? sig.substring(lt + 1, dot) : "?";
            // collapse to 3-segment package
            String[] parts = pkg.split("\\.");
            String key = parts.length >= 3 ? parts[0] + "." + parts[1] + "." + parts[2] : pkg;
            hist.merge(key, 1, Integer::sum);
        }
        hist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> System.out.printf("     %5d  %s%n", e.getValue(), e.getKey()));
        for (String probe : new String[]{"SegmentReader", "FSDirectory", "LuindexHarness", "lucene"}) {
            System.out.printf("     probe %-14s log=%d none=%d gt=%d string-const=%d%n",
                    probe, count(log, probe), count(none, probe), count(gt, probe), count(sc, probe));
        }
    }

    private static int count(Set<String> set, String needle) {
        return (int) set.stream().filter(s -> s.contains(needle)).count();
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> s = Sets.newSet(a);
        s.removeAll(b);
        return s;
    }

    private static int countRecovered(Set<String> config, Set<String> gt) {
        return (int) gt.stream().filter(config::contains).count();
    }

    private static double recall(Set<String> config, Set<String> gt) {
        return gt.isEmpty() ? 1.0 : (double) countRecovered(config, gt) / gt.size();
    }

    private static String cp(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            File f = new File(HOME, p);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                out.add(f.getPath());
            } else if (f.isDirectory()) {
                out.add(f.getPath());
                File[] cs = f.listFiles();
                if (cs != null) {
                    for (File c : cs) {
                        if (c.getName().endsWith(".jar")) {
                            out.add(c.getPath());
                        }
                    }
                }
            }
        }
        return String.join(File.pathSeparator, out);
    }
}
