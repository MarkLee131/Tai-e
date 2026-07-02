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

import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.AnalysisModelPlugin;
import pascal.taie.analysis.pta.plugin.util.CSObjs;
import pascal.taie.analysis.pta.plugin.util.InvokeHandler;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static pascal.taie.analysis.pta.plugin.util.InvokeUtils.BASE;

/**
 * Models {@code java.util.ServiceLoader} (D3). At {@code ServiceLoader.load(I)} it
 * reads {@code META-INF/services/<I>} from the analysis classpath to obtain the
 * provider implementation classes, creates a provider object per implementation,
 * and wires the {@code load → iterator() → next()} chain so that
 * {@code for (I p : ServiceLoader.load(I)) p.foo()} resolves {@code p.foo()} to the
 * providers' methods. Deterministic and sound (only adds provider objects/edges).
 */
public class ServiceLoaderModel extends AnalysisModelPlugin {

    private static final Descriptor SL_OBJ = () -> "ServiceLoaderObj";
    private static final Descriptor SL_ITR = () -> "ServiceLoaderIterator";
    private static final Descriptor SL_PROVIDER = () -> "ServiceProviderObj";

    /** ServiceLoader/Iterator mock obj → its provider impl objects. */
    private final MultiMap<Obj, Obj> providers = Maps.newMultiMap();

    /**
     * Monotone-completion links: providers can be recorded AFTER iterator()/next()
     * already fired (the interface Class objs arrive across solver iterations, but the
     * mock loader/iterator objs never change, so those handlers never re-fire). We keep
     * the delivery graph — loader → iterators, iterator → next()-result vars — and push
     * late providers through it eagerly instead of snapshotting.
     */
    private final MultiMap<Obj, Obj> slIters = Maps.newMultiMap();

    private final MultiMap<Obj, pascal.taie.util.collection.Pair<Context, Var>> nextSites =
            Maps.newMultiMap();

    ServiceLoaderModel(Solver solver) {
        super(solver);
    }

    private static boolean isMock(Obj obj, Descriptor desc) {
        return obj instanceof pascal.taie.analysis.pta.core.heap.MockObj m
                && m.getDescriptor().equals(desc);
    }

    /** Push a provider that just appeared under {@code sl} to all linked iterators. */
    private void deliverToIters(Obj sl, Obj p) {
        for (Obj it : slIters.get(sl)) {
            if (providers.put(it, p)) {
                deliverToNextSites(it, p);
            }
        }
    }

    /** Push a provider that just appeared under iterator {@code it} to next() results. */
    private void deliverToNextSites(Obj it, Obj p) {
        for (var site : nextSites.get(it)) {
            solver.addVarPointsTo(site.first(), site.second(),
                    solver.getCSManager().getCSObj(site.first(), p));
        }
    }

    @InvokeHandler(signature = {
            "<java.util.ServiceLoader: java.util.ServiceLoader load(java.lang.Class)>",
            "<java.util.ServiceLoader: java.util.ServiceLoader load(java.lang.Class,java.lang.ClassLoader)>",
            "<java.util.ServiceLoader: java.util.ServiceLoader loadInstalled(java.lang.Class)>"},
            argIndexes = {0})
    public void load(Context context, Invoke invoke, PointsToSet ifaceClasses) {
        Var result = invoke.getResult();
        if (result == null) {
            return;
        }
        Obj sl = solver.getHeapModel().getMockObj(SL_OBJ, invoke,
                World.get().getTypeSystem().getType("java.util.ServiceLoader"),
                invoke.getContainer());
        ifaceClasses.forEach(co -> {
            JClass iface = CSObjs.toClass(co);
            if (iface == null) {
                return;
            }
            for (String impl : readServiceFile(iface.getName())) {
                JClass c = World.get().getClassHierarchy().getClass(impl);
                if (c == null) {
                    continue; // not loaded → drop (sound)
                }
                solver.initializeClass(c);
                Obj p = solver.getHeapModel().getMockObj(SL_PROVIDER, "service:" + impl,
                        c.getType(), invoke.getContainer());
                if (providers.put(sl, p)) {
                    // late provider (iface Class arrived after iterator()/next() fired):
                    // push through the delivery graph instead of relying on a re-fire
                    // that will never come.
                    deliverToIters(sl, p);
                }
            }
        });
        solver.addVarPointsTo(context, result, sl);
    }

    @InvokeHandler(signature = "<java.util.ServiceLoader: java.util.Iterator iterator()>",
            argIndexes = {BASE})
    public void iterator(Context context, Invoke invoke, PointsToSet loaders) {
        Var result = invoke.getResult();
        if (result == null) {
            return;
        }
        loaders.forEach(csLoader -> {
            Obj loader = csLoader.getObject();
            if (!isMock(loader, SL_OBJ)) {
                return; // not a ServiceLoader we created
            }
            Obj it = solver.getHeapModel().getMockObj(SL_ITR, invoke,
                    World.get().getTypeSystem().getType("java.util.Iterator"),
                    invoke.getContainer());
            if (slIters.put(loader, it)) {
                providers.get(loader).forEach(p -> {
                    if (providers.put(it, p)) {
                        deliverToNextSites(it, p);
                    }
                });
            }
            solver.addVarPointsTo(context, result, it);
        });
    }

    @InvokeHandler(signature = "<java.util.Iterator: java.lang.Object next()>",
            argIndexes = {BASE})
    public void next(Context context, Invoke invoke, PointsToSet iterators) {
        Var result = invoke.getResult();
        if (result == null) {
            return;
        }
        iterators.forEach(csIt -> {
            Obj it = csIt.getObject();
            if (!isMock(it, SL_ITR)) {
                return; // not a ServiceLoader iterator
            }
            // Record the delivery point FIRST, so providers arriving later are pushed
            // here by deliverToNextSites; then deliver the current set.
            nextSites.put(it, new pascal.taie.util.collection.Pair<>(context, result));
            providers.get(it).forEach(p ->
                    solver.addVarPointsTo(context, result, solver.getCSManager().getCSObj(context, p)));
        });
    }

    /** Reads provider class names from {@code META-INF/services/<iface>} on the classpath. */
    private static List<String> readServiceFile(String iface) {
        String resource = "META-INF/services/" + iface;
        List<String> impls = new ArrayList<>();
        Set<String> seen = Sets.newSet();
        for (String entry : classpathEntries()) {
            File f = new File(entry);
            if (f.isDirectory()) {
                File sf = new File(f, resource);
                if (sf.isFile()) {
                    try (InputStream in = Files.newInputStream(sf.toPath())) {
                        parse(in, impls, seen);
                    } catch (IOException | RuntimeException ignored) {
                        // skip
                    }
                }
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                try (JarFile jf = new JarFile(f)) {
                    JarEntry e = jf.getJarEntry(resource);
                    if (e != null) {
                        try (InputStream in = jf.getInputStream(e)) {
                            parse(in, impls, seen);
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // skip
                }
            }
        }
        return impls;
    }

    private static void parse(InputStream in, List<String> impls, Set<String> seen)
            throws IOException {
        for (String raw : new String(in.readAllBytes()).split("\n")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (!line.isEmpty() && seen.add(line)) {
                impls.add(line);
            }
        }
    }

    private static List<String> classpathEntries() {
        List<String> e = new ArrayList<>();
        try {
            e.addAll(World.get().getOptions().getAppClassPath());
            e.addAll(World.get().getOptions().getClassPath());
        } catch (RuntimeException ignored) {
            // ignore
        }
        String root = System.getProperty("arm2.configRoot");
        if (root != null && !root.isBlank()) {
            e.add(root);
        }
        return e;
    }
}
