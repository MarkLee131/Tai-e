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

import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.NullLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Catch;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.ir.stmt.Throw;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ζ1 verifier rung — Check of the M1 formal core at fixture scale
 * (spec Definition II.24): a proposed Σ is admitted iff
 * <ol>
 * <li>Σ is call-closed over the RESOLVED callee sets: every resolved
 *     callee of a proposed body has a proposed summary (the app part of an
 *     open dispatch is a callback atom, not a closure obligation);</li>
 * <li>every proposed method has a body free of refusal-class effects
 *     (§II.2.7 at fixture scale: anything outside the fixture atom
 *     language refuses — expressibility refusal, never truncation);</li>
 * <li>{@code F#_Σ(m) ⊑ σ(m)} per method — the POST-FIXPOINT condition.
 *     {@code F#_Σ(m)} is ONE intra-method abstract pass over
 *     {@code body(m)} (a flow-insensitive fixpoint in the finite origin
 *     domain, §II.2.1), whose only Σ-input is the proposed CALLEE
 *     summaries (Σ-resolution for calls within the slice) — X-independent
 *     (IF-1: it never reads points-to/call-graph results).</li>
 * </ol>
 * No minimality is required: over-approximating (over-claiming) summaries
 * are admitted; under-claiming summaries are refused.
 */
public final class SummaryChecker {

    /** Obligation-NM analog: locally-sound no-op models (empty effect). */
    private static final Set<String> NO_OP_MODELS = Set.of(
            "<java.lang.Object: void <init>()>");

    private final ClassHierarchy hierarchy;

    private final Set<String> sliceClassNames;

    public SummaryChecker(ClassHierarchy hierarchy,
                          Set<String> sliceClassNames) {
        this.hierarchy = hierarchy;
        this.sliceClassNames = Set.copyOf(sliceClassNames);
    }

    // ---------- the origin domain (Definition II.8 at d = 0) ----------

    private sealed interface Origin {
        record Param(int idx) implements Origin {
        }

        record Fresh(String siteId, String className) implements Origin {
        }

        /** the untrackable ⊤-origin (unk); also conflates cbret(d). */
        record Unk() implements Origin {
        }
    }

    private static final Origin.Unk UNK = new Origin.Unk();

    /** Internal control-flow for refusal (never escapes {@link #check}). */
    private static final class RefusalException extends RuntimeException {
        final CheckReport.Reason reason;

        RefusalException(CheckReport.Reason reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    // ---------- Check (Definition II.24) ----------

    /**
     * Checks a proposed Σ (signature-keyed, as an oracle produces it).
     *
     * @return the whole-Σ verdict with refusals and computed effects
     */
    public CheckReport check(Map<String, Set<SummaryAtom>> proposed) {
        List<CheckReport.Refusal> refusals = new ArrayList<>();
        Map<String, Set<SummaryAtom>> computed = new LinkedHashMap<>();
        // resolve signatures; unresolvable proposals are malformed
        Map<JMethod, Set<SummaryAtom>> sigma = new LinkedHashMap<>();
        proposed.forEach((sig, atoms) -> {
            JMethod method = resolveSignature(sig);
            if (method == null) {
                refusals.add(new CheckReport.Refusal(sig,
                        CheckReport.Reason.MALFORMED,
                        "signature does not resolve to a slice method"));
            } else {
                sigma.put(method, atoms);
            }
        });
        for (var entry : sigma.entrySet()) {
            JMethod method = entry.getKey();
            Set<SummaryAtom> atoms = entry.getValue();
            String sig = method.getSignature();
            try {
                validateProposal(method, atoms);
                Set<SummaryAtom> effect = computeEffect(method, sigma);
                computed.put(sig, effect);
                // the post-fixpoint comparison: F#_Σ(m) ⊑ σ(m)
                Set<SummaryAtom> missing = new LinkedHashSet<>(effect);
                missing.removeAll(atoms);
                if (!missing.isEmpty()) {
                    refusals.add(new CheckReport.Refusal(sig,
                            CheckReport.Reason.UNDER_CLAIM,
                            "computed effect not covered by proposal: "
                                    + missing));
                }
            } catch (RefusalException e) {
                refusals.add(new CheckReport.Refusal(sig, e.reason,
                        e.getMessage()));
            }
        }
        return refusals.isEmpty()
                ? CheckReport.admittedReport(
                        sigma.keySet().stream().map(JMethod::getSignature)
                                .collect(LinkedHashSet::new, Set::add, Set::addAll),
                        computed)
                : CheckReport.refusedReport(refusals, computed);
    }

    private JMethod resolveSignature(String signature) {
        if (!signature.startsWith("<") || !signature.contains(":")) {
            return null;
        }
        String className =
                signature.substring(1, signature.indexOf(':')).trim();
        if (!sliceClassNames.contains(className)) {
            return null;
        }
        JClass clazz = hierarchy.getClass(className);
        if (clazz == null) {
            return null;
        }
        return clazz.getDeclaredMethods().stream()
                .filter(m -> m.getSignature().equals(signature))
                .findFirst().orElse(null);
    }

    /** Well-formedness (NOT minimality) of the proposed atoms. */
    private void validateProposal(JMethod method, Set<SummaryAtom> atoms) {
        for (SummaryAtom atom : atoms) {
            if (atom instanceof SummaryAtom.RetAlias retAlias) {
                int idx = retAlias.paramIdx();
                if (idx < 0 || idx >= method.getParamCount()
                        || !(method.getReturnType() instanceof ReferenceType)
                        || !(method.getParamType(idx) instanceof ReferenceType)) {
                    refuse(CheckReport.Reason.MALFORMED,
                            atom + " ill-formed for " + method);
                }
            } else if (atom instanceof SummaryAtom.RetFresh retFresh) {
                if (retFresh.siteId().isEmpty()
                        || hierarchy.getClass(retFresh.className()) == null
                        || !(method.getReturnType() instanceof ReferenceType)) {
                    refuse(CheckReport.Reason.MALFORMED,
                            atom + " ill-formed for " + method);
                }
            } else if (atom instanceof SummaryAtom.Callback callback) {
                int idx = callback.paramIdx();
                if (idx < 0 || idx >= method.getParamCount()
                        || callback.samSubsignature().isEmpty()
                        || !(method.getParamType(idx) instanceof ReferenceType)) {
                    refuse(CheckReport.Reason.MALFORMED,
                            atom + " ill-formed for " + method);
                }
            }
        }
    }

    // ---------- F#_Σ(m): one intra-method pass (§II.2.1) ----------

    /**
     * Computes {@code F#_Σ(m)}: the boundary effect of {@code body(m)}
     * against the proposed callee summaries, as a flow-insensitive
     * fixpoint in the finite origin domain. Throws {@link RefusalException}
     * on call-closure violation or atom-inexpressible effect.
     */
    private Set<SummaryAtom> computeEffect(
            JMethod method, Map<JMethod, Set<SummaryAtom>> sigma) {
        if (method.isAbstract() || method.isNative()) {
            refuse(CheckReport.Reason.BODY_ABSENT,
                    "body(m) absent for " + method);
        }
        IR ir = method.getIR();
        Map<Var, Set<Origin>> env = new LinkedHashMap<>();
        for (int i = 0; i < ir.getParams().size(); i++) {
            envOf(env, ir.getParam(i)).add(new Origin.Param(i));
        }
        if (!method.isStatic()) {
            envOf(env, ir.getThis()).add(UNK);
        }
        Map<New, String> siteIds = numberSites(method, ir);
        Set<SummaryAtom> atoms = new LinkedHashSet<>();
        Set<Origin> retOrigins = new LinkedHashSet<>();
        Set<String> escapedSites = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Stmt stmt : ir) {
                changed |= transfer(method, stmt, env, siteIds, atoms,
                        retOrigins, escapedSites, sigma);
            }
        }
        // return-origin atoms (Definition II.9(a) at fixture scale)
        for (Origin origin : retOrigins) {
            if (origin instanceof Origin.Param param) {
                atoms.add(new SummaryAtom.RetAlias(param.idx()));
            } else if (origin instanceof Origin.Fresh fresh) {
                atoms.add(new SummaryAtom.RetFresh(
                        fresh.siteId(), fresh.className()));
            } else {
                refuse(CheckReport.Reason.INEXPRESSIBLE,
                        "return of untrackable origin exceeds the fixture"
                                + " atom language (depth bound d = 0)");
            }
        }
        return atoms;
    }

    /** GLOBALLY-unique, deterministic site ids: sig + "/new C/" + ordinal. */
    private static Map<New, String> numberSites(JMethod method, IR ir) {
        Map<New, String> siteIds = new LinkedHashMap<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (Stmt stmt : ir) {
            if (stmt instanceof New alloc) {
                String className = alloc.getRValue().getType().getName();
                int ordinal = ordinals.merge(className, 1, Integer::sum) - 1;
                siteIds.put(alloc, method.getSignature()
                        + "/new " + className + "/" + ordinal);
            }
        }
        return siteIds;
    }

    /** One abstract transformer application; returns whether state grew. */
    private boolean transfer(JMethod method, Stmt stmt,
                             Map<Var, Set<Origin>> env,
                             Map<New, String> siteIds,
                             Set<SummaryAtom> atoms,
                             Set<Origin> retOrigins,
                             Set<String> escapedSites,
                             Map<JMethod, Set<SummaryAtom>> sigma) {
        if (stmt instanceof New alloc) {
            return envOf(env, alloc.getLValue()).add(new Origin.Fresh(
                    siteIds.get(alloc),
                    alloc.getRValue().getType().getName()));
        } else if (stmt instanceof Copy copy) {
            return isRef(copy.getLValue()) && envOf(env, copy.getLValue())
                    .addAll(envOf(env, copy.getRValue()));
        } else if (stmt instanceof Cast cast) {
            return isRef(cast.getLValue()) && envOf(env, cast.getLValue())
                    .addAll(envOf(env, cast.getRValue().getValue()));
        } else if (stmt instanceof AssignLiteral assign) {
            // null adds no origin; other reference literals are untrackable
            return isRef(assign.getLValue())
                    && !(assign.getRValue() instanceof NullLiteral)
                    && envOf(env, assign.getLValue()).add(UNK);
        } else if (stmt instanceof LoadField load) {
            // entry paths deeper than the root are outside d = 0: unk
            return isRef(load.getLValue())
                    && envOf(env, load.getLValue()).add(UNK);
        } else if (stmt instanceof LoadArray load) {
            return isRef(load.getLValue())
                    && envOf(env, load.getLValue()).add(UNK);
        } else if (stmt instanceof StoreField store) {
            if (isRef(store.getRValue())) {
                refuse(CheckReport.Reason.INEXPRESSIBLE,
                        "field-write atom outside the fixture summary"
                                + " language: " + stmt);
            }
            return false;
        } else if (stmt instanceof StoreArray store) {
            if (isRef(store.getRValue())) {
                refuse(CheckReport.Reason.INEXPRESSIBLE,
                        "array-write atom outside the fixture summary"
                                + " language: " + stmt);
            }
            return false;
        } else if (stmt instanceof Invoke invoke) {
            return transferInvoke(method, invoke, env, atoms,
                    escapedSites, sigma);
        } else if (stmt instanceof Return ret) {
            return ret.getValue() != null && isRef(ret.getValue())
                    && retOrigins.addAll(envOf(env, ret.getValue()));
        } else if (stmt instanceof Throw) {
            refuse(CheckReport.Reason.INEXPRESSIBLE,
                    "throw atom outside the fixture summary language: " + stmt);
            return false;
        } else if (stmt instanceof Catch caught) {
            return envOf(env, caught.getExceptionRef()).add(UNK);
        } else if (stmt instanceof DefinitionStmt<?, ?> def
                && def.getLValue() instanceof Var lhs && isRef(lhs)) {
            // sound default: unmodeled reference-producing definitions
            refuse(CheckReport.Reason.INEXPRESSIBLE,
                    "unmodeled definition: " + stmt);
            return false;
        }
        // Goto/If/Nop/Monitor/primitive definitions: no pointer effect
        return false;
    }

    /**
     * Call transformer: Σ-resolution and dispatch classification
     * (Definition II.23 at fixture scale).
     */
    private boolean transferInvoke(JMethod method, Invoke invoke,
                                   Map<Var, Set<Origin>> env,
                                   Set<SummaryAtom> atoms,
                                   Set<String> escapedSites,
                                   Map<JMethod, Set<SummaryAtom>> sigma) {
        MethodRef ref = invoke.getMethodRef();
        if (invoke.isDynamic()) {
            refuse(CheckReport.Reason.INEXPRESSIBLE,
                    "invokedynamic outside fixture scope: " + invoke);
        }
        if (invoke.isStatic() || invoke.isSpecial()) {
            // statically-resolved callee: a closure obligation
            JMethod callee = ref.resolveNullable();
            if (callee == null) {
                refuse(CheckReport.Reason.CALL_CLOSURE,
                        "unresolvable callee " + ref);
            }
            if (NO_OP_MODELS.contains(callee.getSignature())) {
                return false; // locally-sound no-op model (Obligation NM analog)
            }
            return applyCalleeSummary(invoke, callee, env, atoms,
                    escapedSites, sigma);
        }
        // virtual/interface dispatch: classify by receiver origins
        Var base = ((InvokeInstanceExp) invoke.getInvokeExp()).getBase();
        boolean changed = false;
        for (Origin origin : List.copyOf(envOf(env, base))) {
            if (origin instanceof Origin.Param param) {
                // open receiver, app part: the callback atom
                // (Definition II.23; cbret conflated to unk at fixture scale)
                changed |= atoms.add(new SummaryAtom.Callback(param.idx(),
                        ref.getSubsignature().toString()));
                changed |= markArgsEscaped(invoke, env, escapedSites);
                if (invoke.getResult() != null && isRef(invoke.getResult())) {
                    changed |= envOf(env, invoke.getResult()).add(UNK);
                }
            } else if (origin instanceof Origin.Fresh fresh) {
                // case (ii): family-fresh receiver — narrow to the tracked
                // allocation class; escape kills family-freshness (α2 R2-3)
                if (escapedSites.contains(fresh.siteId())) {
                    refuse(CheckReport.Reason.INEXPRESSIBLE,
                            "escaped receiver kills family-freshness at "
                                    + invoke);
                }
                JClass cls = hierarchy.getClass(fresh.className());
                JMethod callee = cls == null
                        ? null : hierarchy.dispatch(cls, ref);
                if (callee == null) {
                    refuse(CheckReport.Reason.CALL_CLOSURE,
                            "family-fresh dispatch unresolved at " + invoke);
                }
                changed |= applyCalleeSummary(invoke, callee, env, atoms,
                        escapedSites, sigma);
            } else {
                // untrackable receiver: the full-cone case (i)/(iii) split
                // is outside the fixture language — refuse (coverage-only
                // deviation from the spec's never-refusing case (iii))
                refuse(CheckReport.Reason.INEXPRESSIBLE,
                        "open dispatch on untrackable receiver (case-(iii)"
                                + " (g)-atom outside fixture language): "
                                + invoke);
            }
        }
        return changed;
    }

    /**
     * Σ-resolution for a resolved callee (§II.2.1 per-callee treatment):
     * the callee must be in the slice AND have a proposed summary
     * (call-closure, Definition II.24 clause 1); its atoms are applied at
     * this call site with origins substituted through the actuals.
     */
    private boolean applyCalleeSummary(Invoke invoke, JMethod callee,
                                       Map<Var, Set<Origin>> env,
                                       Set<SummaryAtom> atoms,
                                       Set<String> escapedSites,
                                       Map<JMethod, Set<SummaryAtom>> sigma) {
        if (!sliceClassNames.contains(callee.getDeclaringClass().getName())) {
            refuse(CheckReport.Reason.CALL_CLOSURE,
                    "resolved callee outside the slice (U∖S): " + callee);
        }
        Set<SummaryAtom> calleeSummary = sigma.get(callee);
        if (calleeSummary == null) {
            refuse(CheckReport.Reason.CALL_CLOSURE,
                    "no proposed summary for resolved callee " + callee);
        }
        boolean changed = false;
        Var result = invoke.getResult();
        for (SummaryAtom atom : calleeSummary) {
            if (atom instanceof SummaryAtom.RetAlias retAlias) {
                if (result != null && isRef(result)) {
                    changed |= envOf(env, result).addAll(envOf(env,
                            invoke.getInvokeExp().getArg(retAlias.paramIdx())));
                }
            } else if (atom instanceof SummaryAtom.RetFresh retFresh) {
                if (result != null && isRef(result)) {
                    changed |= envOf(env, result).add(new Origin.Fresh(
                            retFresh.siteId(), retFresh.className()));
                }
            } else if (atom instanceof SummaryAtom.Callback callback) {
                // the callee dispatches on its parameter idx: substitute
                // the actual's origins into the caller's frame
                for (Origin origin : envOf(env,
                        invoke.getInvokeExp().getArg(callback.paramIdx()))) {
                    if (origin instanceof Origin.Param callerParam) {
                        changed |= atoms.add(new SummaryAtom.Callback(
                                callerParam.idx(), callback.samSubsignature()));
                    } else {
                        refuse(CheckReport.Reason.INEXPRESSIBLE,
                                "callback receiver origin " + origin
                                        + " not expressible in the caller's"
                                        + " summary at " + invoke);
                    }
                }
                changed |= markArgsEscaped(invoke, env, escapedSites);
            }
        }
        return changed;
    }

    /** Escape: fresh origins reaching a callback's arguments (α2 R2-3). */
    private static boolean markArgsEscaped(Invoke invoke,
                                           Map<Var, Set<Origin>> env,
                                           Set<String> escapedSites) {
        boolean changed = false;
        for (Var arg : invoke.getInvokeExp().getArgs()) {
            for (Origin origin : envOf(env, arg)) {
                if (origin instanceof Origin.Fresh fresh) {
                    changed |= escapedSites.add(fresh.siteId());
                }
            }
        }
        return changed;
    }

    // ---------- small helpers ----------

    private static Set<Origin> envOf(Map<Var, Set<Origin>> env, Var var) {
        return env.computeIfAbsent(var, v -> new LinkedHashSet<>());
    }

    private static boolean isRef(Var var) {
        return var.getType() instanceof ReferenceType;
    }

    private static void refuse(CheckReport.Reason reason, String detail) {
        throw new RefusalException(reason, detail);
    }
}
