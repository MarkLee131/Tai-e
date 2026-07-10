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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Verdict of {@link SummaryChecker#check} on a proposed Σ — the whole-Σ
 * admission of Definition II.24: Σ is admitted iff every clause holds for
 * every proposed method; otherwise the refusals list the violations.
 * Refusal never affects soundness — it costs coverage (§II.2.7).
 *
 * @param admitted       whether Σ is admitted (a per-method post-fixpoint,
 *                       call-closed, refusal-free)
 * @param refusals       the violations (empty iff admitted)
 * @param coveredMethods dom(Σ) if admitted, empty otherwise — the coverage
 *                       this admission buys (the master theorem's coverage
 *                       clause, Theorem II.30)
 * @param computedAtoms  the checker's computed effect F#_Σ(m) per method
 *                       (canonical strings; methods aborted by refusal are
 *                       absent), for reporting/determinism only
 */
public record CheckReport(
        boolean admitted,
        List<Refusal> refusals,
        SortedSet<String> coveredMethods,
        SortedMap<String, SortedSet<String>> computedAtoms) {

    /** Refusal classes at fixture scale (spec §II.2.7 + Definition II.24). */
    public enum Reason {
        /** body(m) absent (abstract/native without model) — d2. */
        BODY_ABSENT,
        /** proposed atom not well-formed for the method's signature. */
        MALFORMED,
        /**
         * a resolved callee has no proposed summary (call-closure
         * precondition, Definition II.24 clause 1), or resolves outside
         * the slice (m' ∈ U∖S, §II.2.1).
         */
        CALL_CLOSURE,
        /**
         * the computed effect exceeds the fixture atom language
         * (expressibility refusal, Definition II.12 — never silent
         * truncation).
         */
        INEXPRESSIBLE,
        /**
         * F#_Σ(m) ⊄ σ(m): the proposal misses a computed effect — NOT a
         * post-fixpoint. (Over-claims, F#_Σ(m) ⊑ σ(m) with extra atoms,
         * are admitted: no minimality is required, Definition II.24.)
         */
        UNDER_CLAIM
    }

    /** One refusal: which method, which clause, and the offending detail. */
    public record Refusal(String methodSig, Reason reason, String detail) {
        @Override
        public String toString() {
            return reason + "[" + methodSig + ": " + detail + "]";
        }
    }

    static CheckReport admittedReport(Set<String> covered,
                                      Map<String, Set<SummaryAtom>> computed) {
        return new CheckReport(true, List.of(),
                new TreeSet<>(covered), canonicalize(computed));
    }

    static CheckReport refusedReport(List<Refusal> refusals,
                                     Map<String, Set<SummaryAtom>> computed) {
        return new CheckReport(false, List.copyOf(refusals),
                new TreeSet<>(), canonicalize(computed));
    }

    private static SortedMap<String, SortedSet<String>> canonicalize(
            Map<String, Set<SummaryAtom>> computed) {
        SortedMap<String, SortedSet<String>> result = new TreeMap<>();
        computed.forEach((sig, atoms) -> {
            SortedSet<String> strings = new TreeSet<>();
            atoms.forEach(atom -> strings.add(atom.toString()));
            result.put(sig, strings);
        });
        return result;
    }

    /** Canonical, order-independent rendering (for determinism checks). */
    public String canonicalString() {
        StringBuilder sb = new StringBuilder();
        sb.append("admitted: ").append(admitted).append('\n');
        sb.append("covered: ").append(coveredMethods).append('\n');
        sb.append("refusals:\n");
        refusals.stream().map(Refusal::toString).sorted()
                .forEach(r -> sb.append("  ").append(r).append('\n'));
        sb.append("computed:\n");
        computedAtoms.forEach((sig, atoms) ->
                sb.append("  ").append(sig).append(" -> ")
                        .append(atoms).append('\n'));
        return sb.toString();
    }
}
