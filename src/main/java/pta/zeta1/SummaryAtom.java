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

/**
 * The ζ1 fixture-scale summary language (M1 formal core, spec Part II
 * §II.1.3-§II.1.4, Definitions II.8/II.9/II.12): the summary atoms a
 * proposed method summary σ(m) may carry, and against which the checker's
 * computed boundary effect {@code F#_Σ(m)} is compared (Definition II.24).
 *
 * <p>Fixture-scale restriction (documented in the ζ1 test-class javadoc):
 * only the three atom forms the fixture library needs — return-alias of an
 * entry parameter, return of a fresh slice allocation (SITE-INDEXED, the B1
 * fix of Definition II.14), and a callback on an entry parameter. Effects
 * outside this language refuse (Definition II.12: expressibility refusal,
 * never silent truncation).
 */
public sealed interface SummaryAtom {

    /**
     * ret-alias(p_i): the return value's origin is the entry path p_i
     * (Definition II.8, origin = entry path; Definition II.9(a)).
     *
     * @param paramIdx index of the formal parameter (0-based, declared
     *                 parameters; the receiver is not an indexable origin
     *                 at fixture scale)
     */
    record RetAlias(int paramIdx) implements SummaryAtom {
        @Override
        public String toString() {
            return "ret-alias(p" + paramIdx + ")";
        }
    }

    /**
     * ret-fresh(a, c): the return value's origin is {@code fresh(a, c)} —
     * the slice allocation site {@code a} (GLOBALLY unique, so a delegation
     * summary may name a callee's site — Definition II.12, B1) of class
     * {@code c}; it materializes as the summary object {@code o_a} indexed
     * by the site alone (Definition II.14).
     *
     * @param siteId    globally-unique allocation site id
     *                  (methodSignature + "/new " + class + "/" + ordinal)
     * @param className class of the allocation
     */
    record RetFresh(String siteId, String className) implements SummaryAtom {
        @Override
        public String toString() {
            return "ret-fresh(" + siteId + ", " + className + ")";
        }
    }

    /**
     * callback(p_i, g): the body dispatches on the entry parameter p_i with
     * SAM subsignature g — the app part of the open-receiver cone
     * (Definition II.23); materialization adds, per receiver object the
     * client derives, a call edge to the app override (mat clause (d),
     * Definition II.25).
     *
     * @param paramIdx         index of the receiver formal parameter
     * @param samSubsignature  subsignature of the invoked method,
     *                         e.g. {@code "void run()"}
     */
    record Callback(int paramIdx, String samSubsignature) implements SummaryAtom {
        @Override
        public String toString() {
            return "callback(p" + paramIdx + ", " + samSubsignature + ")";
        }
    }
}
