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

import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Objectively models the static context obtainable for an Unknown reflective NAME
 * (the {@code String} flowing into {@code forName}/{@code getMethod}/{@code getField})
 * by backward-tracing its definition through the IR. Two scenarios:
 *
 * <ul>
 *   <li><b>String construction</b> ({@code "p." + x}, {@code StringBuilder.append})
 *       → the constant fragments. The name var often has an EMPTY points-to set
 *       (string ops are not modeled), so this is the only available evidence.</li>
 *   <li><b>Config-driven</b> ({@code Properties.getProperty(key)},
 *       {@code getResourceAsStream(name)}, {@code System.getProperty(key)}) → the
 *       constant key / resource name.</li>
 * </ul>
 *
 * <p>It then assesses evidence <b>quality</b>: HIGH when there is at least one
 * substantial constant fragment (a likely package/identifier), LOW when the name
 * is opaque. The caller feeds HIGH-quality evidence directly and preprocesses
 * LOW-quality evidence into a best-effort summary before querying the LLM.
 */
final class ReflectionContextExtractor {

    /** Bound on backward-trace steps (avoids cycles / runaway). */
    private static final int MAX_STEPS = 60;

    /**
     * Modeled use-site name evidence.
     *
     * @param fragments    constant string fragments recovered (identifiers, keys, …)
     * @param fromConfig   the name flows from a property/map lookup
     * @param fromResource the name flows from a resource (file) read
     * @param highQuality  whether a substantial constant fragment is present
     * @param promptText   the (quality-gated) context block to feed the LLM
     */
    record Context(List<String> fragments, boolean fromConfig, boolean fromResource,
                   boolean highQuality, String promptText) {}

    private ReflectionContextExtractor() {
    }

    static Context extract(Var nameVar, JMethod container) {
        IR ir = container.getIR();
        Set<String> fragments = Sets.newLinkedSet();
        boolean[] fromConfig = {false};
        boolean[] fromResource = {false};

        Deque<Var> work = new ArrayDeque<>();
        Set<Var> seen = Sets.newSet();
        work.add(nameVar);
        seen.add(nameVar);
        int steps = 0;
        while (!work.isEmpty() && steps++ < MAX_STEPS) {
            Var v = work.poll();
            for (Stmt s : ir.getStmts()) {
                if (s.getDef().filter(d -> d.equals(v)).isEmpty()) {
                    continue;
                }
                if (s instanceof AssignLiteral al
                        && al.getRValue() instanceof StringLiteral sl) {
                    addFragment(fragments, sl.getString());
                } else if (s instanceof Copy cp) {
                    enqueue(work, seen, cp.getRValue());
                } else if (s instanceof Invoke inv) {
                    traceInvoke(inv, work, seen, fromConfig, fromResource);
                }
            }
        }
        return classify(fragments, fromConfig[0], fromResource[0]);
    }

    private static void traceInvoke(Invoke inv, Deque<Var> work, Set<Var> seen,
                                    boolean[] fromConfig, boolean[] fromResource) {
        MethodRef ref = inv.getMethodRef();
        String dc = ref.getDeclaringClass().getName();
        String mn = ref.getName();
        InvokeExp exp = inv.getInvokeExp();
        boolean sb = dc.equals("java.lang.StringBuilder") || dc.equals("java.lang.StringBuffer");
        if (sb || dc.equals("java.lang.String")) {
            // toString/append/<init>: follow the receiver and all args.
            if (exp instanceof InvokeInstanceExp ii) {
                enqueue(work, seen, ii.getBase());
            }
            exp.getArgs().forEach(a -> enqueue(work, seen, a));
        } else if (mn.equals("getProperty") || mn.equals("getString")
                || (mn.equals("get") && dc.contains("Map"))) {
            // Properties/Map lookup: the key fragment carries the signal.
            fromConfig[0] = true;
            exp.getArgs().forEach(a -> enqueue(work, seen, a));
        } else if (mn.contains("getResource") || mn.contains("ResourceAsStream")) {
            fromResource[0] = true;
            exp.getArgs().forEach(a -> enqueue(work, seen, a));
        }
        // otherwise opaque: no further evidence from this def
    }

    private static void enqueue(Deque<Var> work, Set<Var> seen, Var v) {
        if (v != null && seen.add(v)) {
            work.add(v);
        }
    }

    private static void addFragment(Set<String> fragments, String s) {
        if (s != null && !s.isEmpty()) {
            fragments.add(s);
        }
    }

    private static Context classify(Set<String> fragments, boolean fromConfig,
                                    boolean fromResource) {
        List<String> frags = new ArrayList<>(fragments);
        // HIGH quality: a substantial constant fragment (likely an identifier /
        // package prefix) is present.
        boolean high = frags.stream().anyMatch(f -> f.length() >= 3 || f.contains("."));
        StringBuilder sb = new StringBuilder();
        String source = fromResource ? "a resource/config file"
                : fromConfig ? "a configuration property" : "string construction";
        sb.append("Use-site name evidence (source: ").append(source).append(").\n");
        if (!frags.isEmpty()) {
            sb.append("Constant string fragments recovered by backward string-flow: ")
                    .append(frags).append('\n');
            if (high) {
                sb.append("These fragments are high-confidence: assemble/complete them "
                        + "(in order, possibly concatenated) into the fully-qualified name.\n");
            } else {
                sb.append("(weak: short/partial fragments — combine with naming "
                        + "conventions and the surrounding code).\n");
            }
        } else {
            sb.append("No static string fragments available (opaque, e.g. read at runtime); "
                    + "rely on naming conventions, the surrounding code, and the use-site "
                    + "type bounds.\n");
        }
        return new Context(frags, fromConfig, fromResource, high, sb.toString());
    }
}
