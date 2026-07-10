package pta.arm2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.ir.IR;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pta.arm2.ConeClosureFeasibilityProbe.Receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for ζ0's tracked/open receiver classifier
 * ({@link ConeClosureFeasibilityProbe#classifyReceiver}) on a tiny fixture
 * (src/test/resources/pta/zeta0/ZetaCone.java). Runs with the ζ0 gradle task;
 * needs java-benchmarks/JREs for world building, like the other harnesses.
 */
public class ConeClassifierTest {

    @BeforeAll
    static void buildWorld() {
        Main.buildWorld("-cp", "src/test/resources/pta/zeta0", "-m", "ZetaCone");
    }

    /** Classifies the single {@code area()} dispatch inside the given fixture method. */
    private static Receiver classify(String methodName) {
        JClass zetaCone = World.get().getClassHierarchy().getClass("ZetaCone");
        assertNotNull(zetaCone, "fixture class not in world");
        JMethod m = zetaCone.getDeclaredMethods().stream()
                .filter(x -> x.getName().equals(methodName))
                .findFirst().orElseThrow();
        IR ir = m.getIR();
        Invoke dispatch = ir.stmts()
                .filter(s -> s instanceof Invoke inv
                        && inv.getInvokeExp().getMethodRef().getName().equals("area"))
                .map(s -> (Invoke) s)
                .findFirst().orElseThrow();
        assertTrue(dispatch.isVirtual() || dispatch.isInterface(),
                "fixture dispatch must be virtual/interface");
        return ConeClosureFeasibilityProbe.classifyReceiver(ir, dispatch);
    }

    @Test
    void uniqueNewIsTracked() {
        Receiver r = classify("trackedSimple");
        assertTrue(r.tracked());
        assertEquals("ZSquare", r.newClass().getName());
    }

    @Test
    void copyChainToNewIsTracked() {
        Receiver r = classify("trackedCopy");
        assertTrue(r.tracked());
        assertEquals("ZSquare", r.newClass().getName());
    }

    @Test
    void parameterReceiverIsOpen() {
        assertFalse(classify("openParam").tracked());
    }

    @Test
    void escapeBeforeDispatchIsOpen() {
        assertFalse(classify("openEscape").tracked());
    }

    @Test
    void multipleDefsAreOpen() {
        assertFalse(classify("openMultiDef").tracked());
    }

    @Test
    void escapeAfterDispatchStaysTracked() {
        Receiver r = classify("trackedEscapeAfter");
        assertTrue(r.tracked());
        assertEquals("ZSquare", r.newClass().getName());
    }
}
