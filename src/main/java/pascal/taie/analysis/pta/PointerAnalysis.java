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

import org.slf4j.event.Level;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelectorFactory;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.solver.DefaultSolver;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.AnalysisTimer;
import pascal.taie.analysis.pta.plugin.ClassInitializer;
import pascal.taie.analysis.pta.plugin.CompositePlugin;
import pascal.taie.analysis.pta.plugin.EntryPointHandler;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.ReferenceHandler;
import pascal.taie.analysis.pta.plugin.ResultProcessor;
import pascal.taie.analysis.pta.plugin.ThreadHandler;
import pascal.taie.analysis.pta.plugin.android.AndroidAnalysis;
import pascal.taie.analysis.pta.plugin.exception.ExceptionAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.InvokeDynamicAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.Java9StringConcatHandler;
import pascal.taie.analysis.pta.plugin.invokedynamic.LambdaAnalysis;
import pascal.taie.analysis.pta.plugin.natives.NativeModeller;
import pascal.taie.analysis.pta.plugin.reflection.ReflectionAnalysis;
import pascal.taie.analysis.pta.plugin.spring.SpringAnalysis;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysis;
import pascal.taie.analysis.pta.toolkit.CollectionMethods;
import pascal.taie.analysis.pta.toolkit.mahjong.Mahjong;
import pascal.taie.analysis.pta.toolkit.scaler.Scaler;
import pascal.taie.analysis.pta.toolkit.zipper.Zipper;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.config.ConfigException;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.Monitor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class PointerAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "pta";

    public PointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        AnalysisOptions options = getOptions();
        HeapModel heapModel = new AllocationSiteBasedModel(options);
        ContextSelector selector = null;
        String advanced = options.getString("advanced");
        String cs = options.getString("cs");
        if (advanced != null) {
            if (advanced.equals("collection")) {
                selector = ContextSelectorFactory.makeSelectiveSelector(cs,
                        new CollectionMethods(World.get().getClassHierarchy()).get());
            } else {
                // run context-insensitive analysis as pre-analysis
                PointerAnalysisResult preResult = runAnalysis(heapModel,
                        ContextSelectorFactory.makeCISelector());
                if (advanced.startsWith("scaler")) {
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeGuidedSelector(Scaler.run(preResult, advanced)),
                            "Scaler", Level.INFO);
                } else if (advanced.startsWith("zipper")) {
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, Zipper.run(preResult, advanced)),
                            "Zipper", Level.INFO);
                } else if (advanced.startsWith("llm")) {
                    // Arm① — LLM-guided selective context-sensitivity.
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, pta.arm1.LlmCsSelector.run(
                                            preResult, advanced,
                                            pta.arm1.ArmOracleFactory.fromOptions(options))),
                            "LLM-CS", Level.INFO);
                } else if (advanced.equals("mahjong")) {
                    heapModel = Monitor.runAndCount(() -> Mahjong.run(preResult, options),
                            "Mahjong", Level.INFO);
                } else if (advanced.equals("cafd")) {
                    // B3 baseline — CAFD-equivalent allocator-wrapper heap refinement.
                    // Per-callsite cloning via AllocatorWrapperModel + AllocatorWrapperPlugin.
                    heapModel = Monitor.runAndCount(
                            () -> pta.baseline.cafd.AllocatorWrapperModel.run(preResult, options),
                            "CAFD", Level.INFO);
                } else {
                    throw new IllegalArgumentException(
                            "Illegal advanced analysis argument: " + advanced);
                }
            }
        }
        if (selector == null) {
            selector = ContextSelectorFactory.makePlainSelector(cs);
        }
        // Arm③ (neuro-symbolic) two-pass: when the LlmFactPlugin is requested,
        // run a sound context-insensitive PRE-ANALYSIS first (without the arm③
        // plugin) so the plugin can build its consistency base and gate its
        // filters on the real points-to relation. Mirrors the advanced:llm /
        // advanced:cafd pre-analysis pattern used by arm① / B3.
        PointerAnalysisResult arm3Pre = null;
        @SuppressWarnings("unchecked")
        List<String> plugins = (List<String>) options.get("plugins");
        if (plugins != null && plugins.contains(ARM3_PLUGIN)) {
            arm3Pre = runAnalysis(heapModel,
                    ContextSelectorFactory.makeCISelector(), null);
        }
        return runAnalysis(heapModel, selector, arm3Pre);
    }

    /** Fully-qualified class name of the arm③ neuro-symbolic plugin. */
    private static final String ARM3_PLUGIN = "pta.arm3.LlmFactPlugin";

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector) {
        return runAnalysis(heapModel, selector, null);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector,
                                              PointerAnalysisResult arm3Pre) {
        AnalysisOptions options = getOptions();
        Solver solver = new DefaultSolver(options,
                heapModel, selector, new MapBasedCSManager());
        // The initialization of some Plugins may read the fields in solver,
        // e.g., contextSelector or csManager, thus we initialize Plugins
        // after setting all other fields of solver.
        setPlugin(solver, options, arm3Pre);
        solver.solve();
        return solver.getResult();
    }

    private static void setPlugin(Solver solver, AnalysisOptions options,
                                  PointerAnalysisResult arm3Pre) {
        CompositePlugin plugin = new CompositePlugin();
        // add builtin plugins
        // To record elapsed time precisely, AnalysisTimer should be added at first.
        plugin.addPlugin(
                new AnalysisTimer(),
                new EntryPointHandler(),
                new ClassInitializer(),
                new ThreadHandler(),
                new NativeModeller(),
                new ExceptionAnalysis()
        );
        if (World.get().getOptions().isAndroidMode()) {
            plugin.addPlugin(new AndroidAnalysis());
        }
        int javaVersion = World.get().getOptions().getJavaVersion();
        if (javaVersion < 9) {
            // current reference handler doesn't support Java 9+
            plugin.addPlugin(new ReferenceHandler());
        }
        if (javaVersion >= 8) {
            plugin.addPlugin(new LambdaAnalysis());
        }
        if (javaVersion >= 9) {
            plugin.addPlugin(new Java9StringConcatHandler());
        }
        if (options.getString("reflection-inference") != null ||
                options.getString("reflection-log") != null) {
            plugin.addPlugin(new ReflectionAnalysis());
        }
        if (options.getBoolean("handle-invokedynamic") &&
                InvokeDynamicAnalysis.useMethodHandle()) {
            plugin.addPlugin(new InvokeDynamicAnalysis());
        }
        if (options.getString("taint-config") != null
                || !((List<String>) options.get("taint-config-providers")).isEmpty()) {
            plugin.addPlugin(new TaintAnalysis());
        }
        if (options.getBoolean("spring")) {
            plugin.addPlugin(new SpringAnalysis());
        }
        plugin.addPlugin(new ResultProcessor());
        // B3 baseline: add the CAFD companion plugin for the main (non-pre) analysis.
        // When advanced:cafd is in options the heap model must be one of:
        //   (a) AllocationSiteBasedModel — the CI pre-analysis pass; silently skip.
        //   (b) AllocatorWrapperModel    — the real CAFD main pass; add the plugin.
        // Any other model is a configuration mismatch: fail fast so it is never
        // silently treated as bare CI.
        if ("cafd".equals(options.getString("advanced"))) {
            HeapModel heapModel = solver.getHeapModel();
            if (heapModel instanceof pta.baseline.cafd.AllocatorWrapperModel cafdModel) {
                plugin.addPlugin(new pta.baseline.cafd.AllocatorWrapperPlugin(cafdModel));
            } else if (!(heapModel instanceof AllocationSiteBasedModel)) {
                throw new IllegalStateException(
                        "advanced:cafd requires AllocatorWrapperModel for the main analysis "
                        + "pass, but got: " + heapModel.getClass().getName());
            }
            // else: AllocationSiteBasedModel = CI pre-analysis pass — skip silently
        }
        // add plugins specified in options.
        // Arm③ (LlmFactPlugin) is handled specially: it is NOT instantiated via
        // the no-arg reflective path because it requires the sound CI pre-analysis
        // result. It is added manually only for the MAIN pass (arm3Pre != null);
        // during the pre-analysis pass (arm3Pre == null) it is skipped, so the
        // base relation is computed by an unrefined, sound analysis.
        // noinspection unchecked
        List<String> pluginClasses =
                new java.util.ArrayList<>((List<String>) options.get("plugins"));
        boolean hasArm3 = pluginClasses.remove(ARM3_PLUGIN);
        addPlugins(plugin, pluginClasses);
        if (hasArm3 && arm3Pre != null) {
            plugin.addPlugin(new pta.arm3.LlmFactPlugin(arm3Pre));
        }
        // connects plugins and solver
        plugin.setSolver(solver);
        solver.setPlugin(plugin);
    }

    private static void addPlugins(CompositePlugin plugin,
                                   List<String> pluginClasses) {
        for (String pluginClass : pluginClasses) {
            try {
                Class<?> clazz = Class.forName(pluginClass);
                Constructor<?> ctor = clazz.getConstructor();
                Plugin newPlugin = (Plugin) ctor.newInstance();
                plugin.addPlugin(newPlugin);
            } catch (ClassNotFoundException e) {
                throw new ConfigException(
                        "Plugin class " + pluginClass + " is not found");
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new AnalysisException("Failed to get constructor of " +
                        pluginClass + ", does the plugin class" +
                        " provide a public non-arg constructor?");
            } catch (InvocationTargetException | InstantiationException e) {
                throw new AnalysisException(
                        "Failed to create plugin instance for " + pluginClass, e);
            }
        }
    }
}
