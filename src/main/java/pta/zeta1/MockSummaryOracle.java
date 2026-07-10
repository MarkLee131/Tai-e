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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mock oracle: a canned, test-provided Σ (no live LLM queries). This is
 * the ζ1 stand-in for an LLM that reads the slice bodies and proposes
 * summaries; the checker treats it as fully untrusted either way.
 */
public final class MockSummaryOracle implements SummaryOracle {

    private final Map<String, Set<SummaryAtom>> canned;

    public MockSummaryOracle(Map<String, Set<SummaryAtom>> canned) {
        // defensive, deterministic copy
        Map<String, Set<SummaryAtom>> copy = new LinkedHashMap<>();
        canned.forEach((sig, atoms) ->
                copy.put(sig, new LinkedHashSet<>(atoms)));
        this.canned = copy;
    }

    @Override
    public Map<String, Set<SummaryAtom>> propose(
            List<String> sliceMethodSignatures) {
        return canned;
    }
}
