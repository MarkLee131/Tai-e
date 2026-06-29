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

package pta.arm1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Arm ① — LLM-guided selective context-sensitivity.
 *
 * <p>Mirrors {@code Zipper.run(PointerAnalysisResult, String)}: from a
 * context-insensitive pre-analysis result, it selects the set of application
 * methods that should be analyzed context-sensitively. The decision for each
 * candidate method is delegated to an {@link pta.llm.LlmOracle}: the method is
 * included iff the first line of the oracle response is {@code YES}.
 *
 * <p><b>Soundness:</b> the returned set is only used to choose <i>which</i>
 * methods receive context sensitivity. A selective context selector is sound
 * and is bounded in precision between context-insensitive (no method selected)
 * and full context-sensitivity (all methods selected); a wrong LLM answer can
 * only shift precision/cost within that interval, never miss a real edge or
 * alias. Hence the "Disposer" is the identity function.
 */
public final class LlmCsSelector {

    private static final Logger logger = LoggerFactory.getLogger(LlmCsSelector.class);

    /**
     * Default cap on the number of methods selected for context sensitivity.
     */
    private static final int DEFAULT_CAP = 100;

    /**
     * Hard bound on the number of oracle queries issued, so the analysis cost
     * stays predictable on large programs.
     */
    private static final int MAX_QUERIES = 2000;

    private LlmCsSelector() {
    }

    /**
     * Parses the {@code advanced} argument and runs the LLM-guided selection.
     *
     * @param pre    context-insensitive pre-analysis result.
     * @param arg    advanced-analysis argument, e.g. {@code "llm"} or
     *               {@code "llm=50"} (the optional suffix is the selection cap).
     * @param oracle the LLM oracle (or a mock) answering YES/NO per method.
     * @return the set of methods to analyze context-sensitively.
     */
    public static Set<JMethod> run(PointerAnalysisResult pre, String arg,
                                   pta.llm.LlmOracle oracle) {
        int cap = parseCap(arg);
        // Deterministic candidate ordering (by signature) so that the cap and
        // re-runs are reproducible.
        List<JMethod> candidates = pre.getCallGraph()
                .reachableMethods()
                .distinct()
                .filter(LlmCsSelector::isApplicationMethod)
                .filter(m -> isReferenceRelevant(m, pre))
                .sorted((a, b) -> a.getSignature().compareTo(b.getSignature()))
                .limit(MAX_QUERIES)
                .collect(Collectors.toList());

        Set<JMethod> selected = new LinkedHashSet<>();
        for (JMethod m : candidates) {
            if (selected.size() >= cap) {
                break;
            }
            String prompt = buildPrompt(m, pre);
            pta.llm.LlmResponse resp = oracle.ask(
                    new pta.llm.LlmQuery("cs-critical", prompt, m.getSignature()));
            if (isYes(resp)) {
                selected.add(m);
            }
        }
        logger.info("LLM-CS selected {} of {} candidate methods (cap={})",
                selected.size(), candidates.size(), cap);
        return selected;
    }

    private static int parseCap(String arg) {
        if (arg != null && arg.contains("=")) {
            try {
                return Math.max(0, Integer.parseInt(arg.substring(arg.indexOf('=') + 1).trim()));
            } catch (NumberFormatException e) {
                logger.warn("Illegal LLM-CS cap in '{}', using default {}", arg, DEFAULT_CAP);
            }
        }
        return DEFAULT_CAP;
    }

    private static boolean isApplicationMethod(JMethod m) {
        return !m.isAbstract()
                && m.getDeclaringClass() != null
                && m.getDeclaringClass().isApplication();
    }

    /**
     * A method is a candidate iff it holds at least one reference-typed local
     * variable with a non-empty points-to set, i.e. it actually participates in
     * object value flow and could plausibly benefit from context sensitivity.
     */
    private static boolean isReferenceRelevant(JMethod m, PointerAnalysisResult pre) {
        for (Var v : m.getIR().getVars()) {
            if (v.getType() instanceof ReferenceType
                    && !pre.getPointsToSet(v).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isYes(pta.llm.LlmResponse resp) {
        List<String> lines = resp.asLines();
        return !lines.isEmpty() && lines.get(0).equalsIgnoreCase("YES");
    }

    /**
     * Builds a bounded prompt from the method signature and its IR body asking
     * whether the method is precision-critical (i.e. worth analyzing
     * context-sensitively).
     */
    private static String buildPrompt(JMethod m, PointerAnalysisResult pre) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are tuning a Java pointer analysis. Decide whether the ")
                .append("following method should be analyzed CONTEXT-SENSITIVELY ")
                .append("to improve precision (e.g. it is a getter/setter/factory/")
                .append("wrapper whose behaviour depends on its caller's objects).\n")
                .append("Answer with the single word YES or NO on the first line.\n\n")
                .append("Method: ").append(m.getSignature()).append('\n')
                .append("Body:\n");
        int n = 0;
        for (Stmt s : m.getIR().getStmts()) {
            sb.append("  ").append(s).append('\n');
            if (++n >= 60) { // bound the body size
                sb.append("  ...\n");
                break;
            }
        }
        return sb.toString();
    }
}
