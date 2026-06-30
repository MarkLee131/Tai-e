package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario A: config-driven reflective names ({@code props.getProperty("plugin.class")}).
 * arm②'s ConfigResolver must read the actual value from the {@code .properties} on
 * the classpath and surface it as a high-confidence candidate. The test oracle
 * echoes that candidate back, so the test passes ONLY if the config content was
 * actually read and fed into the prompt.
 */
public class ReflectionConfigTest {

    /** Echoes the config candidate the resolver placed in the prompt. */
    private static final LlmOracle ECHO_CONFIG = q -> {
        Matcher m = Pattern.compile("candidates\\): \\[([^,\\]]+)").matcher(q.prompt());
        return new LlmResponse(m.find() ? m.group(1) : "", false, 0.0);
    };

    private static Set<String> reachable(String main, String... opts) {
        Tests.testPTA(false, "reflection", main, opts);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void resolvesConfigDrivenNamesFromPropertiesFile() {
        // Ensure the resolver can locate the resource even if the analysis cp does not list it.
        System.setProperty("arm2.configRoot", "src/test/resources/pta/reflection");
        try {
            Set<String> baseline = reachable("ArmReflectionConfig",
                    "reflection-inference:string-constant");
            assertFalse(baseline.contains("PlugImpl.go"),
                    "non-vacuity: baseline must miss PlugImpl.go");

            LlmInferenceModel.setOracle(ECHO_CONFIG);
            Set<String> withArm;
            try {
                withArm = reachable("ArmReflectionConfig", "reflection-inference:llm");
            } finally {
                LlmInferenceModel.clearOracle();
            }
            assertTrue(withArm.contains("PlugImpl.go"),
                    "ConfigResolver must read plugin.class/plugin.method from arm2cfg.properties "
                            + "so PlugImpl.go is reachable; got " + withArm);
            assertTrue(withArm.contains("PlugImpl.mark"),
                    "the invoke edge must fire so go()'s body is analyzed → mark() reachable");
            assertTrue(withArm.containsAll(baseline), "SOUNDNESS: add-only");
        } finally {
            System.clearProperty("arm2.configRoot");
        }
    }
}
