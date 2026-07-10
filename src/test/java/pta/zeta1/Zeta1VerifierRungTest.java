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

package pta.zeta1;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ζ1 — the fixture-scale verifier-rung prototype (M1 plan): LLM-mock
 * proposes summaries, a sound checker admits post-fixpoints
 * ({@code F#_Σ(m) ⊑ σ(m)}, spec Definition II.24), admitted summaries are
 * injected into the client pointer analysis (spec Definition II.25).
 *
 * <p><b>Semantics demonstrated (the over/under-claim asymmetry).</b>
 * Check is a per-method POST-FIXPOINT condition, not an equality: a summary
 * that OVER-claims (proposes atoms beyond the computed effect) is still a
 * post-fixpoint and is ADMITTED — the checker admits over-approximations,
 * trading precision, never soundness. A summary that UNDER-claims (misses a
 * computed effect: {@code F#_Σ(m) ⊄ σ(m)}) is REFUSED — that is the sound
 * rejection this rung exists for. See
 * {@link #underClaimRefusedButOverClaimAdmitted()}.
 *
 * <p><b>Deltas vs the spec (2026-07-11-m1-formal-core.md, Part II) —
 * honest fixture-scale simplifications; none contradicts the spec's
 * soundness direction (refusal costs coverage, never soundness, §II.2.7):</b>
 * <ol>
 * <li><b>Atom language restricted</b> to ret-alias(p_i), ret-fresh(a, c)
 *     with globally-site-indexed o_a (Definitions II.8/II.12/II.14), and
 *     callback(p_i, SAM-subsignature). Field-write, static-write, throw,
 *     reflective-event and open-dispatch (g)-atoms are NOT in the fixture
 *     language; effects needing them REFUSE (expressibility refusal,
 *     Definition II.12 / §II.2.7 — never silent truncation).</li>
 * <li><b>Depth bound d = 0</b>: entry paths are parameter roots only;
 *     field/array loads yield the untrackable origin (unk), whose escape
 *     into a return refuses.</li>
 * <li><b>Case-(iii) open dispatch refuses</b> instead of emitting a
 *     (g)-atom (the (g)-atom is outside the fixture language). The spec
 *     says case (iii) NEVER refuses; at fixture scale this substitution is
 *     coverage-only — refusal is always sound. The fixture bodies contain
 *     no case-(iii) dispatch (their open receivers are functional
 *     parameters, handled as callback atoms).</li>
 * <li><b>Callback atom carries (paramIdx, SAM subsignature)</b> rather than
 *     the checker-computed app-part target SET of the CHA cone
 *     (Definition II.23); materialization resolves app overrides per
 *     receiver object from the client's points-to at injection time —
 *     faithful to mat clause (d) (Definition II.25). Lib-part cone members
 *     are not dispatched (they would be case (i)/(iii) in the full
 *     instance). {@code cbret(d)} is conflated to unk, so callback return
 *     values escaping into the summary refuse.</li>
 * <li><b>Obligation NM analog</b>: one locally-sound no-op native/JDK model,
 *     {@code java.lang.Object.<init>()} (empty effect) — the constructor
 *     chain's terminal, mirroring the spec's 4-6 named JDK native models.</li>
 * <li><b>Whole-Σ admission verdict</b> (Definition II.24 verbatim: Σ is
 *     admitted or not); the policy layer that salvages admissible subsets
 *     (Part I §I.6) is out of ζ1 scope.</li>
 * <li><b>Escape havoc (α2 R2-3)</b> implemented minimally: a fresh origin
 *     escaping through a callback kills family-freshness; a later dispatch
 *     on it refuses (instead of havoc-then-full-cone).</li>
 * <li><b>Client is context-insensitive</b>; o_a materializes as a solver
 *     MockObj keyed by the GLOBAL site id alone, so the same site proposed
 *     by different methods' summaries yields the identical abstract object
 *     (the B1 site-indexing fix, Definition II.14) — asserted in
 *     {@link #correctSummariesAdmittedAndCoverageGrows()} via
 *     pts(b1) == pts(b2).</li>
 * </ol>
 */
public class Zeta1VerifierRungTest {

    private static final String DIR = "zeta1";

    private static final String MAIN = "ZetaMain";

    private static final String PLUGIN =
            "plugins:[" + SummaryInjectionPlugin.class.getName() + "]";

    private static final List<String> SLICE = List.of("ZLib", "ZBox");

    // ---------- summary sets the mock oracle proposes ----------

    private static final String ID_SIG = "<ZLib: java.lang.Object id(java.lang.Object)>";
    private static final String MAKE_SIG = "<ZLib: ZBox make()>";
    private static final String WRAP_SIG = "<ZLib: ZBox wrap()>";
    private static final String EACH_SIG = "<ZLib: void each(java.lang.Runnable)>";
    private static final String ZBOX_INIT_SIG = "<ZBox: void <init>()>";

    /** The globally-unique fresh site: ZLib.make's (only) `new ZBox`. */
    private static final String MAKE_SITE = MAKE_SIG + "/new ZBox/0";

    /** The sound, exact summary set for the whole slice. */
    private static Map<String, Set<SummaryAtom>> goodSigma() {
        Map<String, Set<SummaryAtom>> sigma = new LinkedHashMap<>();
        sigma.put(ID_SIG, Set.of(new SummaryAtom.RetAlias(0)));
        sigma.put(MAKE_SIG, Set.of(new SummaryAtom.RetFresh(MAKE_SITE, "ZBox")));
        // wrap's summary names a site NOT in wrap's body (B1 cross-method marker)
        sigma.put(WRAP_SIG, Set.of(new SummaryAtom.RetFresh(MAKE_SITE, "ZBox")));
        sigma.put(EACH_SIG, Set.of(new SummaryAtom.Callback(0, "void run()")));
        sigma.put(ZBOX_INIT_SIG, Set.of());
        return sigma;
    }

    // ---------- helpers ----------

    private static void run(Map<String, Set<SummaryAtom>> proposed) {
        SummaryInjectionPlugin.configure(SLICE,
                new MockSummaryOracle(proposed));
        Tests.testPTA(false, DIR, MAIN, PLUGIN);
    }

    private static PointerAnalysisResult result() {
        return World.get().getResult(PointerAnalysis.ID);
    }

    private static JMethod mainMethod() {
        return World.get().getClassHierarchy().getClass(MAIN)
                .getDeclaredMethod("main");
    }

    /** The app-side call site in main() whose callee has the given name. */
    private static Invoke callTo(String calleeName) {
        for (Stmt stmt : mainMethod().getIR()) {
            if (stmt instanceof Invoke invoke
                    && invoke.getMethodRef().getName().equals(calleeName)) {
                return invoke;
            }
        }
        throw new AssertionError("no call to " + calleeName + " in main");
    }

    /** CI points-to set of the result variable of main's call to callee. */
    private static Set<Obj> ptsOfResult(String calleeName) {
        return result().getPointsToSet(callTo(calleeName).getResult());
    }

    private static Set<String> ptsStrings(String calleeName) {
        return ptsOfResult(calleeName).stream()
                .map(Obj::toString)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isReachable(String className, String methodName) {
        var clazz = World.get().getClassHierarchy().getClass(className);
        if (clazz == null) {
            return false;
        }
        JMethod m = clazz.getDeclaredMethod(methodName);
        return m != null && result().getCallGraph().contains(m);
    }

    // ---------- (a) correct summaries: admitted + coverage grows ----------

    @Test
    void correctSummariesAdmittedAndCoverageGrows() {
        // Baseline: empty Σ — the slice is opaque and nothing is injected.
        run(Map.of());
        CheckReport baseline = SummaryInjectionPlugin.getLastReport();
        assertTrue(baseline.admitted(), "empty Σ is vacuously admitted");
        assertEquals(0, baseline.coveredMethods().size());
        assertTrue(ptsOfResult("id").isEmpty(),
                "opaque boundary: pts(r1) must be empty without summaries");
        assertTrue(ptsOfResult("make").isEmpty());
        assertTrue(ptsOfResult("wrap").isEmpty());
        assertFalse(isReachable("ZTask", "run"),
                "callback edge must not exist without the callback atom");

        // Correct Σ: admitted, and the app-side analysis grows.
        run(goodSigma());
        CheckReport report = SummaryInjectionPlugin.getLastReport();
        assertTrue(report.admitted(),
                "correct summary set must be admitted: " + report.refusals());
        assertEquals(5, report.coveredMethods().size(),
                "coverage grows from 0 to the whole slice");

        // ret-alias: pts(r1) == pts(o) == { the app allocation }.
        Set<Obj> ptsO = result().getPointsToSet(
                callTo("id").getInvokeExp().getArg(0));
        assertEquals(1, ptsO.size());
        assertEquals(ptsO, ptsOfResult("id"),
                "ret-alias(0) must alias the app-side actual");

        // ret-fresh: pts(b1) = { o_a }, SITE-INDEXED: pts(b2) is the SAME o_a
        // although b2 comes from wrap() (cross-method site marker, B1).
        Set<Obj> ptsB1 = ptsOfResult("make");
        Set<Obj> ptsB2 = ptsOfResult("wrap");
        assertEquals(1, ptsB1.size(), "make() returns exactly o_a");
        assertEquals(ptsB1, ptsB2,
                "site-indexed o_a: make() and wrap() yield the identical object");
        Obj oa = ptsB1.iterator().next();
        assertEquals(MAKE_SITE, oa.getAllocation().toString(),
                "o_a is indexed by the globally-unique site id");
        assertEquals("ZBox", oa.getType().getName());
    }

    // ---------- (b) the over/under-claim asymmetry ----------

    @Test
    void underClaimRefusedButOverClaimAdmitted() {
        // UNDER-claim: id's summary misses its computed ret-alias effect.
        // F#_Σ(id) = {ret-alias(0)} ⊄ σ(id) = {} — NOT a post-fixpoint: REFUSED.
        Map<String, Set<SummaryAtom>> under = goodSigma();
        under.put(ID_SIG, Set.of());
        run(under);
        CheckReport report = SummaryInjectionPlugin.getLastReport();
        assertFalse(report.admitted(), "under-claiming summary must be refused");
        assertTrue(report.refusals().stream().anyMatch(r ->
                        r.methodSig().equals(ID_SIG)
                                && r.reason() == CheckReport.Reason.UNDER_CLAIM),
                "refusal must be an UNDER_CLAIM on id: " + report.refusals());
        // Refused Σ injects nothing (whole-Σ verdict, Definition II.24).
        assertTrue(ptsOfResult("id").isEmpty());
        assertTrue(ptsOfResult("make").isEmpty());

        // OVER-claim: id's summary claims a ret-fresh it does not have.
        // F#_Σ(id) = {ret-alias(0)} ⊑ σ(id) = {ret-alias(0), ret-fresh(a, ZBox)}
        // — still a post-fixpoint: ADMITTED (the checker admits
        // over-approximations; no minimality is required, Definition II.24).
        Map<String, Set<SummaryAtom>> over = goodSigma();
        over.put(ID_SIG, new LinkedHashSet<>(List.of(
                new SummaryAtom.RetAlias(0),
                new SummaryAtom.RetFresh(MAKE_SITE, "ZBox"))));
        run(over);
        report = SummaryInjectionPlugin.getLastReport();
        assertTrue(report.admitted(),
                "over-claiming summary is a post-fixpoint and must be admitted: "
                        + report.refusals());
        // The over-claim is injected: pts(r1) ⊇ {app alloc, o_a} — an
        // over-approximation (precision cost), never a soundness loss.
        Set<Obj> ptsR1 = ptsOfResult("id");
        assertEquals(2, ptsR1.size(),
                "over-claimed summary injects both the alias and o_a");
    }

    // ---------- (c) call-closure refusal ----------

    @Test
    void callClosureRefusedOnMissingCalleeSummary() {
        // wrap()'s body resolves the call to make(), but Σ has no σ(make):
        // the call-closure precondition (Definition II.24, clause 1) fails.
        Map<String, Set<SummaryAtom>> sigma = goodSigma();
        sigma.remove(MAKE_SIG);
        run(sigma);
        CheckReport report = SummaryInjectionPlugin.getLastReport();
        assertFalse(report.admitted(), "missing callee summary must refuse");
        assertTrue(report.refusals().stream().anyMatch(r ->
                        r.methodSig().equals(WRAP_SIG)
                                && r.reason() == CheckReport.Reason.CALL_CLOSURE),
                "refusal must be CALL_CLOSURE on wrap: " + report.refusals());
        assertTrue(ptsOfResult("wrap").isEmpty(), "refused Σ injects nothing");

        // Also via the constructor chain: make()'s `new ZBox()` resolves
        // ZBox.<init>; dropping its summary breaks closure at make.
        sigma = goodSigma();
        sigma.remove(ZBOX_INIT_SIG);
        run(sigma);
        report = SummaryInjectionPlugin.getLastReport();
        assertFalse(report.admitted());
        assertTrue(report.refusals().stream().anyMatch(r ->
                        r.methodSig().equals(MAKE_SIG)
                                && r.reason() == CheckReport.Reason.CALL_CLOSURE),
                "constructor closure: " + report.refusals());
    }

    // ---------- (d) callback atom: soundly closed ----------

    @Test
    void callbackAtomRequiredAndClientSeesCallbackEdge() {
        // each()'s body invokes its Runnable parameter; a summary WITHOUT
        // the callback atom under-claims F#_Σ(each) and must be refused.
        Map<String, Set<SummaryAtom>> noCallback = goodSigma();
        noCallback.put(EACH_SIG, Set.of());
        run(noCallback);
        CheckReport report = SummaryInjectionPlugin.getLastReport();
        assertFalse(report.admitted(),
                "summary hiding the callback must be refused");
        assertTrue(report.refusals().stream().anyMatch(r ->
                        r.methodSig().equals(EACH_SIG)
                                && r.reason() == CheckReport.Reason.UNDER_CLAIM),
                "callback omission is an UNDER_CLAIM on each: "
                        + report.refusals());
        assertFalse(isReachable("ZTask", "run"));

        // With the callback atom admitted, materialization (mat clause (d))
        // resolves the app override on the receiver objects: the client
        // sees the callback edge and run()'s effects.
        run(goodSigma());
        report = SummaryInjectionPlugin.getLastReport();
        assertTrue(report.admitted());
        assertTrue(isReachable("ZTask", "run"),
                "callback edge must make ZTask.run reachable");
        JMethod runMethod = World.get().getClassHierarchy()
                .getClass("ZTask").getDeclaredMethod("run");
        assertTrue(result().getCallGraph()
                        .getCalleesOf(callTo("each")).contains(runMethod),
                "the callback edge is attached to the app-side call site");
    }

    // ---------- (e) determinism ----------

    @Test
    void twoRunsAreByteIdentical() {
        String first = runAndFingerprint();
        String second = runAndFingerprint();
        assertEquals(first, second,
                "two runs of the verifier rung must be byte-identical");
        assertNotNull(first);
        assertFalse(first.isEmpty());
    }

    private static String runAndFingerprint() {
        run(goodSigma());
        StringBuilder sb = new StringBuilder();
        sb.append(SummaryInjectionPlugin.getLastReport().canonicalString());
        sb.append("\n--- injections ---\n");
        SummaryInjectionPlugin.getInjectionLog().stream().sorted()
                .forEach(line -> sb.append(line).append('\n'));
        sb.append("--- app-side pts ---\n");
        for (String callee : List.of("id", "make", "wrap")) {
            sb.append(callee).append(" -> ")
                    .append(ptsStrings(callee)).append('\n');
        }
        sb.append("--- callees of each ---\n");
        result().getCallGraph().getCalleesOf(callTo("each")).stream()
                .map(JMethod::toString).sorted()
                .forEach(s -> sb.append(s).append('\n'));
        return sb.toString();
    }
}
