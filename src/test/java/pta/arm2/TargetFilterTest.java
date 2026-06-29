package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.TypeSystem;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@link TargetFilter} (the soundness gate). No solver.
 */
public class TargetFilterTest {

    private static Invoke findRunInvoke(ClassHierarchy h) {
        JClass client = h.getClass("IfaceTargets");
        assertNotNull(client, "IfaceTargets must be loaded");
        JMethod main = client.getDeclaredMethod("main");
        assertNotNull(main, "main must be present");
        for (Stmt stmt : main.getIR().getStmts()) {
            if (stmt instanceof Invoke invoke
                    && "run".equals(invoke.getMethodRef().getName())) {
                return invoke;
            }
        }
        throw new AssertionError("no run() invoke found in IfaceTargets.main");
    }

    @Test
    void admitKeepsLoadedCompatibleDropsRest() {
        Main.buildWorld("-cp", "src/test/resources/pta/arm2",
                "--input-classes", "IfaceTargets");
        ClassHierarchy h = World.get().getClassHierarchy();
        TypeSystem ts = World.get().getTypeSystem();
        Invoke runSite = findRunInvoke(h);

        Set<JMethod> admitted = new TargetFilter()
                .admit(runSite, List.of("Foo", "Bogus", "Baz"), h, ts);

        Set<String> classes = admitted.stream()
                .map(m -> m.getDeclaringClass().getName())
                .collect(Collectors.toSet());
        // Foo and Baz are loaded and define a compatible run(); Bogus is dropped.
        assertEquals(Set.of("Foo", "Baz"), classes);
        // every admitted target really is the run() method (signature-compatible).
        assertTrue(admitted.stream().allMatch(m -> m.getName().equals("run")),
                "all admitted targets must be run()");
    }
}
