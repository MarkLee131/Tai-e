package pta.arm2;

import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Arm② §11 step 2 — gathers {@link ReflectionEvidence} (Elf "self-inferencing"
 * use-site signals) for a {@link FlaggedSite}: the post-dominating downcast on
 * the result, the reflective receiver's type, and surrounding context.
 *
 * <p>This is a first cut: the downcast is matched directly on the site's result
 * variable. Cross-statement value-flow (downcast on a copied/returned var) and
 * arg-array element types / cross-site string-flow hints are the next
 * enrichment (see {@code 2026-07-01-guiding-ideas-from-reflection-papers.md} §2).
 */
public final class EvidenceCollector {

    /** IR statements before/after the site included for prompt context. */
    private static final int CONTEXT_WINDOW = 2;

    private EvidenceCollector() {
    }

    /**
     * Gathers evidence purely from the site's IR (downcast, receiver type,
     * surrounding code), so it works both on a finished result and mid-analysis
     * inside a plugin.
     */
    public static ReflectionEvidence collect(FlaggedSite site) {
        Invoke invoke = site.site();
        IR ir = site.container().getIR();
        String downcast = findDowncastType(ir, invoke.getResult());
        String receiver = receiverType(site);
        List<String> surrounding = surroundingStmts(ir, invoke);
        return new ReflectionEvidence(downcast, receiver, surrounding);
    }

    /** FQ type of a downcast applied directly to {@code resultVar}, or null. */
    private static String findDowncastType(IR ir, Var resultVar) {
        if (resultVar == null) {
            return null;
        }
        for (Stmt s : ir.getStmts()) {
            if (s instanceof Cast cast && cast.getRValue().getValue().equals(resultVar)) {
                return cast.getRValue().getCastType().getName();
            }
        }
        return null;
    }

    /** Static type of the reflective receiver argument (invoke/get/set), or null. */
    private static String receiverType(FlaggedSite site) {
        switch (site.kind()) {
            case INVOKE, FIELD_GET, FIELD_SET -> {
                InvokeExp exp = site.site().getInvokeExp();
                return exp.getArgCount() > 0 ? exp.getArg(0).getType().getName() : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** IR statements within {@link #CONTEXT_WINDOW} of the site, as strings. */
    private static List<String> surroundingStmts(IR ir, Invoke invoke) {
        int idx = invoke.getIndex();
        List<String> out = new ArrayList<>();
        for (Stmt s : ir.getStmts()) {
            int i = s.getIndex();
            if (i >= idx - CONTEXT_WINDOW && i <= idx + CONTEXT_WINDOW) {
                out.add(s.toString());
            }
        }
        return out;
    }
}
