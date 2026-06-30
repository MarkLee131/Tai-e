package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3: ServiceLoaderModel reads META-INF/services and wires the load→iterator→next
 * chain so a provider's methods become reachable through {@code ServiceLoader.load}.
 */
public class ServiceLoaderModelTest {

    @Test
    void resolvesServiceProviderMethods() {
        System.setProperty("arm2.configRoot", "src/test/resources/pta/reflection");
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionService",
                    "reflection-inference:string-constant");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            Set<String> reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
            assertTrue(reachable.contains("PluginImpl.run"),
                    "ServiceLoaderModel must resolve the provider so PluginImpl.run is reachable; got "
                            + reachable);
            assertTrue(reachable.contains("PluginImpl.done"),
                    "the provider's method body must be analyzed → done() reachable");
        } finally {
            System.clearProperty("arm2.configRoot");
        }
    }
}
