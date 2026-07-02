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
import pascal.taie.World;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.CompositePlugin;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.MapEntry;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.Comparator;
import java.util.Set;

public class ReflectionAnalysis extends CompositePlugin {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionAnalysis.class);

    private static final int DEFAULT_IMPRECISE_THRESHOLD = 50;

    private static final int ANDROID_IMPRECISE_THRESHOLD = 5;

    private static boolean ignoreImpreciseTarget = false;

    private LogBasedModel logBasedModel;

    private InferenceModel inferenceModel;

    private ReflectiveActionModel reflectiveActionModel;

    /**
     * @return short name of reflection API in given {@link Invoke}.
     */
    public static String getShortName(Invoke invoke) {
        MethodRef ref = invoke.getMethodRef();
        String className = ref.getDeclaringClass().getSimpleName();
        String methodName = ref.getName();
        return className + "." + methodName;
    }

    @Override
    public void setSolver(Solver solver) {
        MetaObjHelper helper = new MetaObjHelper(solver);
        TypeMatcher typeMatcher = new TypeMatcher(solver.getTypeSystem());
        String logPath = solver.getOptions().getString("reflection-log");
        logBasedModel = new LogBasedModel(solver, helper, logPath);
        Set<Invoke> invokesWithLog = logBasedModel.getInvokesWithLog();
        String reflection = solver.getOptions().getString("reflection-inference");
        if ("string-constant".equals(reflection)) {
            inferenceModel = new StringBasedModel(solver, helper, invokesWithLog);
        } else if ("solar".equals(reflection)) {
            inferenceModel = new SolarModel(solver, helper, typeMatcher, invokesWithLog);
        } else if ("llm".equals(reflection)) {
            inferenceModel = new LlmInferenceModel(solver, helper, invokesWithLog);
            // OPTIONAL composition (-Darm2.solarCompose): Reflex ON TOP of Solar.
            // Solar's collective TYPE inference (Method.invoke / newInstance) resolves
            // the type-gated reflection Reflex's NAME resolution does not target (e.g.
            // the log4j plugin dispatch behind Log4Shell). Both models are add-only and
            // monotone, so the composition is sound and recall is monotone (>=). But
            // Solar's forName also re-introduces its unknown-class OVER-APPROXIMATION,
            // measurably costing precision (0.01-0.19 on DaCapo), so composition is
            // OPT-IN, not the default: the high-precision Reflex-alone configuration is
            // the headline. A surgical composition inheriting only Solar's type-based
            // Method.invoke inference (without its forName c^u) is future work.
            if (System.getProperty("arm2.solarCompose") != null) {
                addPlugin(new SolarModel(solver, helper, typeMatcher, invokesWithLog));
            }
        } else if (reflection == null) {
            inferenceModel = InferenceModel.getDummy(solver);
        } else {
            throw new IllegalArgumentException("Illegal reflection option: " + reflection);
        }
        reflectiveActionModel = new ReflectiveActionModel(solver, helper,
                typeMatcher, invokesWithLog);

        if (isAndroidMode()) {
            ignoreImpreciseTarget = true;
        }

        addPlugin(logBasedModel,
                inferenceModel,
                reflectiveActionModel,
                new OthersModel(solver, helper));
        // arm②'s deterministic add-ons (ServiceLoader + self-inference) attach only for
        // reflection-inference:llm (or explicit -Darm2.addons): the shipped baselines
        // (null / string-constant / solar / log) keep VANILLA Tai-e semantics, so
        // published-baseline comparisons and reproductions are uncontaminated.
        if ("llm".equals(reflection) || System.getProperty("arm2.addons") != null) {
            // per-component ablation switches for controlled experiments (RQ3)
            if (System.getProperty("arm2.ablate.serviceLoader") == null) {
                addPlugin(new ServiceLoaderModel(solver));
            }
            if (System.getProperty("arm2.ablate.selfInference") == null) {
                addPlugin(new SelfInferenceModel(solver));
            }
        }
        // The SOLAR-N3 ledgers are static (read by the eval harness across runs): reset
        // them on EVERY analysis construction, not just llm runs, so a baseline run never
        // reports the previous llm run's residuals.
        LlmInferenceModel.resetLedgers();

        if (World.get().getOptions().getJavaVersion() >= 5) {
            addPlugin(new AnnotationModel(solver, helper));
        }
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        if (!isAndroidMode()) {
            super.onNewStmt(stmt, container);
            return;
        }

        if (stmt instanceof Invoke invoke
                && !invoke.isDynamic()
                && container.isApplication()) {
            super.onNewStmt(stmt, container);
        }
    }

    @Override
    public void onFinish() {
        super.onFinish();
        reportImpreciseCalls();
        if (System.getProperty("arm2.dumpTargets") != null
                && inferenceModel instanceof LlmInferenceModel) {
            inferenceModel.getForNameTargets().forEach((invoke, clazz) ->
                    logger.info("[refl-target] {}#{} -> {}",
                            invoke.getContainer().getSignature(), invoke.getIndex(),
                            clazz.getName()));
        }
    }

    /**
     * Report that may be resolved imprecisely.
     */
    private void reportImpreciseCalls() {
        MultiMap<Invoke, Object> allTargets = collectAllTargets();
        Set<Invoke> invokesWithLog = logBasedModel.getInvokesWithLog();
        var impreciseCalls = allTargets.keySet()
                .stream()
                .map(invoke -> new MapEntry<>(invoke, allTargets.get(invoke)))
                .filter(e -> !invokesWithLog.contains(e.getKey()))
                .filter(e -> reachesImpreciseThreshold(e.getValue().size()))
                .toList();
        if (!impreciseCalls.isEmpty()) {
            logger.info("Imprecise reflective calls:");
            impreciseCalls.stream()
                    .sorted(Comparator.comparingInt(
                            (MapEntry<Invoke, Set<Object>> e) -> -e.getValue().size())
                            .thenComparing(MapEntry::getKey))
                    .forEach(e -> {
                        Invoke invoke = e.getKey();
                        String shortName = getShortName(invoke);
                        logger.info("[{}]{}, #targets: {}",
                                shortName, invoke, e.getValue().size());
                    });
        }
    }

    /**
     * Collects all reflective targets resolved by reflection analysis.
     */
    private MultiMap<Invoke, Object> collectAllTargets() {
        MultiMap<Invoke, Object> allTargets = Maps.newMultiMap();
        allTargets.putAll(logBasedModel.getForNameTargets());
        allTargets.putAll(inferenceModel.getForNameTargets());
        allTargets.putAll(reflectiveActionModel.getAllTargets());
        return allTargets;
    }

    static boolean isAndroidMode() {
        return World.get().getOptions().isAndroidMode();
    }

    private static boolean reachesImpreciseThreshold(int targetCount) {
        return targetCount >= (isAndroidMode()
                ? ANDROID_IMPRECISE_THRESHOLD
                : DEFAULT_IMPRECISE_THRESHOLD);
    }

    static boolean ignoreImpreciseTarget(int targetCount) {
        return ignoreImpreciseTarget && reachesImpreciseThreshold(targetCount);
    }

}
