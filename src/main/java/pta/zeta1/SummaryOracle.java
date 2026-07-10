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

/**
 * The LLM slot of the ζ1 verifier rung: an oracle that PROPOSES a summary
 * set Σ for the library slice. Proposals are untrusted — every proposal
 * passes through {@link SummaryChecker} (Definition II.24) and only
 * admitted post-fixpoints are injected. The oracle can therefore be
 * arbitrarily wrong without affecting soundness (it costs coverage only).
 */
public interface SummaryOracle {

    /**
     * Proposes summaries for (a subset of) the slice methods.
     *
     * @param sliceMethodSignatures signatures of the methods in the
     *                              proposed slice S, in deterministic order
     * @return proposed Σ: method signature ↦ set of summary atoms
     */
    Map<String, Set<SummaryAtom>> propose(List<String> sliceMethodSignatures);
}
