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

package pta.arm1;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.language.classes.JMethod;
import pta.llm.MockOracle;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link LlmCsSelector}: a {@link MockOracle} answers "YES"
 * for exactly one method id and "NO" otherwise; the selector must return
 * exactly that method (Disposer is identity).
 */
public class LlmCsSelectorTest {

    @Test
    void selectsExactlyTheYesMethod() {
        // Build a context-insensitive pre-analysis result for OneObject.
        Tests.testPTA(false, "contextsensitivity", "OneObject", "cs:ci");
        PointerAnalysisResult pre = World.get().getResult(PointerAnalysis.ID);

        // Pick a known application method ("A.get()") as the YES target.
        JMethod target = pre.getCallGraph().reachableMethods()
                .filter(m -> m.getDeclaringClass().isApplication())
                .filter(m -> m.getSignature().contains("get()"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("A.get() not reachable"));

        MockOracle oracle = new MockOracle(
                Map.of(target.getSignature(), "YES"), "NO");

        Set<JMethod> selected = LlmCsSelector.run(pre, "llm", oracle);

        assertEquals(Set.of(target), selected,
                "selector must return exactly the YES-answered method");
        assertTrue(selected.contains(target));
    }
}
