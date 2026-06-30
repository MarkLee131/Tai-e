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

package pascal.taie.analysis.pta.plugin.reflection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.CSObjs;
import pascal.taie.analysis.pta.plugin.util.InvokeHandler;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pta.llm.LlmOracle;
import pta.llm.LlmQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static pascal.taie.analysis.pta.plugin.util.InvokeUtils.BASE;

/**
 * Arm② — LLM-augmented reflection inference (integrated into Tai-e's reflection
 * stack). Resolves reflective NAMES like {@link StringBasedModel} for string
 * constants, and additionally asks an {@link LlmOracle} for the residual sites
 * whose class/method name is input-dependent ("Unknown"), where static
 * resolution fails. Resolved names are turned into Class/Method metaobjects via
 * the inherited {@code classForNameKnown}/{@code classGetMethodKnown}, so
 * {@link ReflectiveActionModel} builds the downstream newInstance/invoke/get/set
 * edges soundly (incl. argument flow, via {@code ReflectiveCallEdge}), and
 * {@link TypeMatcher} clamps targets by the use-site downcast/argument types.
 *
 * <p><b>Sound by construction:</b> the LLM only ADDS candidate names; an
 * unloadable name resolves to nothing ({@code hierarchy.getClass} returns null),
 * and the existing type matcher / call-graph machinery never drop a statically-
 * required edge. A wrong LLM answer costs at most precision.
 *
 * <p>Activated by option {@code reflection-inference:llm}. The oracle is injected
 * via {@link #setOracle} (tests/mock) or built live from {@link pta.llm.ApiKeyResolver}.
 */
public class LlmInferenceModel extends InferenceModel {

    private static final Logger logger = LoggerFactory.getLogger(LlmInferenceModel.class);

    /** Max IR statements of the enclosing method included as prompt context. */
    private static final int CONTEXT_STMTS = 40;

    private static volatile LlmOracle oracleOverride;

    public static void setOracle(LlmOracle oracle) {
        oracleOverride = oracle;
    }

    public static void clearOracle() {
        oracleOverride = null;
    }

    private final LlmOracle oracle;

    /** Sites already queried (dedup the LLM call across pts-change re-fires). */
    private final Set<Invoke> queried = new HashSet<>();

    LlmInferenceModel(Solver solver, MetaObjHelper helper, Set<Invoke> invokesWithLog) {
        super(solver, helper, invokesWithLog);
        this.oracle = resolveOracle();
    }

    private static LlmOracle resolveOracle() {
        if (oracleOverride != null) {
            return oracleOverride;
        }
        String key = pta.llm.ApiKeyResolver.resolve();
        if (key.isEmpty()) {
            return null; // no key, no override → resolves only constants (sound no-op for LLM)
        }
        return new pta.llm.GeminiOracle("gemini-2.5-flash", key,
                new pta.llm.PromptCache(java.nio.file.Path.of(".llm-cache")),
                new pta.llm.CostMeter(100.0, 3e-7, 2.5e-6));
    }

    // -----------------------------------------------------------------------
    // forName / loadClass — resolve the (possibly Unknown) class name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.Class forName(java.lang.String)>",
            "<java.lang.Class: java.lang.Class forName(java.lang.String,boolean,java.lang.ClassLoader)>",
            "<java.lang.ClassLoader: java.lang.Class loadClass(java.lang.String)>"},
            argIndexes = {0})
    public void classForName(Context context, Invoke invoke, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        boolean[] knownName = {false};
        nameObjs.forEach(obj -> {
            String s = CSObjs.toString(obj);
            if (s != null) {
                classForNameKnown(context, invoke, s);
                knownName[0] = true;
            }
        });
        // Residual: name is input-dependent → ask the LLM, inject its proposals.
        if (!knownName[0] && oracle != null && queried.add(invoke)) {
            for (String className : askLlm("llm-class", invoke,
                    "A reflective Class.forName(...) has a non-constant class name. "
                            + "Given the surrounding code, list the fully-qualified names of "
                            + "the classes it may load, one per line.")) {
                classForNameKnown(context, invoke, className.trim());
            }
        }
    }

    // -----------------------------------------------------------------------
    // getMethod / getDeclaredMethod — resolve the (possibly Unknown) method name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Method getMethod(java.lang.String,java.lang.Class[])>",
            "<java.lang.Class: java.lang.reflect.Method getDeclaredMethod(java.lang.String,java.lang.Class[])>"},
            argIndexes = {BASE, 0})
    public void classGetMethod(Context context, Invoke invoke,
                               PointsToSet classObjs, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        List<JClass> classes = new ArrayList<>();
        classObjs.forEach(co -> {
            JClass clazz = CSObjs.toClass(co);
            if (clazz != null) {
                classes.add(clazz);
            }
        });
        boolean[] knownName = {false};
        classes.forEach(clazz -> nameObjs.forEach(no -> {
            String name = CSObjs.toString(no);
            if (name != null) {
                classGetMethodKnown(context, invoke, clazz, name);
                knownName[0] = true;
            }
        }));
        // Residual: method name input-dependent and at least one class is known.
        if (!knownName[0] && !classes.isEmpty() && oracle != null && queried.add(invoke)) {
            for (String name : askLlm("llm-method", invoke,
                    "A reflective getMethod(...) has a non-constant method name. "
                            + "Given the surrounding code, list the method name(s) it may "
                            + "retrieve, one per line.")) {
                for (JClass clazz : classes) {
                    classGetMethodKnown(context, invoke, clazz, name.trim());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // getField / getDeclaredField — resolve the (possibly Unknown) field name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Field getField(java.lang.String)>",
            "<java.lang.Class: java.lang.reflect.Field getDeclaredField(java.lang.String)>"},
            argIndexes = {BASE, 0})
    public void classGetField(Context context, Invoke invoke,
                              PointsToSet classObjs, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        List<JClass> classes = new ArrayList<>();
        classObjs.forEach(co -> {
            JClass clazz = CSObjs.toClass(co);
            if (clazz != null) {
                classes.add(clazz);
            }
        });
        boolean[] knownName = {false};
        classes.forEach(clazz -> nameObjs.forEach(no -> {
            String name = CSObjs.toString(no);
            if (name != null) {
                classGetFieldKnown(context, invoke, clazz, name);
                knownName[0] = true;
            }
        }));
        if (!knownName[0] && !classes.isEmpty() && oracle != null && queried.add(invoke)) {
            for (String name : askLlm("llm-field", invoke,
                    "A reflective getField(...) has a non-constant field name. "
                            + "Given the surrounding code, list the field name(s) it may "
                            + "retrieve, one per line.")) {
                for (JClass clazz : classes) {
                    classGetFieldKnown(context, invoke, clazz, name.trim());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // getConstructor — no name to resolve; mirror StringBasedModel for known class
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Constructor getConstructor(java.lang.Class[])>",
            "<java.lang.Class: java.lang.reflect.Constructor getDeclaredConstructor(java.lang.Class[])>"},
            argIndexes = {BASE})
    public void classGetConstructor(Context context, Invoke invoke, PointsToSet classObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        classObjs.forEach(co -> classGetConstructorKnown(context, invoke, CSObjs.toClass(co)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<String> askLlm(String kind, Invoke invoke, String question) {
        String siteId = invoke.getContainer().getSignature() + "@" + invoke.getIndex();
        String prompt = question + "\nSite: " + siteId + "\nEnclosing method body:\n" + body(invoke);
        try {
            List<String> lines = oracle.ask(new LlmQuery(kind, prompt, siteId)).asLines();
            logger.info("[arm2-llm] {} at {} → {}", kind, siteId, lines);
            return lines;
        } catch (RuntimeException e) {
            logger.warn("[arm2-llm] query failed for {}: {}", siteId, e.getMessage());
            return List.of();
        }
    }

    private static String body(Invoke invoke) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Stmt s : invoke.getContainer().getIR().getStmts()) {
            sb.append("  ").append(s).append('\n');
            if (++n >= CONTEXT_STMTS) {
                sb.append("  ...\n");
                break;
            }
        }
        return sb.toString();
    }
}
