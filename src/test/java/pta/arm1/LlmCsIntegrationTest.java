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
import pascal.taie.ir.exp.Var;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Arm ①: the {@code advanced:llm} branch in
 * {@link PointerAnalysis} wired through {@link ArmOracleFactory} (MockOracle
 * loaded from a canned file). Asserts the LLM-selected precision is bounded
 * between context-insensitive and full {@code 2-obj}, and is deterministic.
 */
public class LlmCsIntegrationTest {

    private static final String DIR = "contextsensitivity";

    private static final String MAIN = "OneObject";

    private static final String MOCK =
            "llm-mock-file:src/test/resources/pta/arm1/oneobject-mock.txt";

    /**
     * Precision proxy: total size of the CI-projected points-to sets of all
     * reachable variables. Fewer pointees => more precise.
     */
    private static long totalPointsTo() {
        PointerAnalysisResult pta = World.get().getResult(PointerAnalysis.ID);
        long sum = 0;
        for (Var v : pta.getVars()) {
            sum += pta.getPointsToSet(v).size();
        }
        return sum;
    }

    @Test
    void precisionIsBetweenCiAndTwoObjAndDeterministic() {
        Tests.testPTA(false, DIR, MAIN, "cs:ci");
        long ci = totalPointsTo();

        Tests.testPTA(false, DIR, MAIN, "cs:2-obj", "advanced:llm", MOCK);
        long llm = totalPointsTo();

        Tests.testPTA(false, DIR, MAIN, "cs:2-obj");
        long twoObj = totalPointsTo();

        // Sanity: this example is precision-discriminating.
        assertTrue(ci > twoObj,
                "expected CI (" + ci + ") strictly less precise than 2-obj (" + twoObj + ")");
        // Soundness/precision bound of selective CS.
        assertTrue(ci >= llm,
                "LLM-selected (" + llm + ") must be at least as precise as CI (" + ci + ")");
        assertTrue(llm >= twoObj,
                "LLM-selected (" + llm + ") cannot exceed full 2-obj precision (" + twoObj + ")");
        // The mock selects the precision-critical A methods, so it should
        // strictly improve over CI on this example.
        assertTrue(llm < ci,
                "LLM selection of A.set/doSet/get should improve over CI");

        // Determinism: re-running the identical LLM configuration is stable.
        Tests.testPTA(false, DIR, MAIN, "cs:2-obj", "advanced:llm", MOCK);
        long llm2 = totalPointsTo();
        assertEquals(llm, llm2, "LLM-guided selection must be deterministic");
    }
}
