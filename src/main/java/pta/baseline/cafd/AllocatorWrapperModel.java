package pta.baseline.cafd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.AbstractHeapModel;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CAFD-equivalent allocator-wrapper heap model (B3 deterministic baseline).
 *
 * <h3>Design</h3>
 * <p>For each call site of a detected allocator-wrapper method, this model
 * pre-creates a distinct {@link pascal.taie.analysis.pta.core.heap.MockObj}
 * keyed by that {@link Invoke} statement.  A companion plugin
 * ({@link AllocatorWrapperPlugin}) then:
 * <ol>
 *   <li>Suppresses normal body analysis of each wrapper method (via
 *       {@link pascal.taie.analysis.pta.core.solver.Solver#addIgnoredMethod}).</li>
 *   <li>On every new call edge to a wrapper, injects the per-callsite MockObj
 *       into the caller's return variable.</li>
 * </ol>
 * The net effect is that variables receiving the result of wrapper calls at
 * <em>different</em> call sites point to <em>distinct</em> abstract objects,
 * reducing alias-set sizes without full context sensitivity.
 *
 * <h3>Integration</h3>
 * Instantiated by {@link pascal.taie.analysis.pta.PointerAnalysis#analyze()}
 * under {@code advanced:cafd}, mirroring the {@code mahjong} branch.
 */
public class AllocatorWrapperModel extends AbstractHeapModel {

    private static final Logger logger =
            LoggerFactory.getLogger(AllocatorWrapperModel.class);

    /** Descriptor that identifies per-callsite clone objects in debug output. */
    static final Descriptor CAFD_DESC = () -> "CAFD-wrapper";

    /** Detected allocator-wrapper methods. */
    private final Set<JMethod> wrappers;

    /**
     * Maps each call site (Invoke from the pre-analysis call graph) to its
     * per-callsite abstract object.  New entries may be added on the fly
     * for call sites discovered during the main analysis but absent from the
     * pre-analysis (see {@link #getOrCreateCallSiteObj}).
     */
    private final Map<Invoke, Obj> callSiteObjs;

    AllocatorWrapperModel(Set<JMethod> wrappers,
                          PointerAnalysisResult preResult,
                          AnalysisOptions options) {
        super(options);
        this.wrappers = wrappers;
        this.callSiteObjs = buildCallSiteObjs(wrappers, preResult);
        logger.info("CAFD: {} wrapper(s) detected, {} per-callsite object(s) pre-created",
                wrappers.size(), callSiteObjs.size());
    }

    // ------------------------------------------------------------------ //

    /**
     * Factory method: runs {@link WrapperDetector} on the pre-analysis result
     * and builds the heap model.
     *
     * @param preResult pre-analysis (CI) result
     * @param options   analysis options (forwarded to {@link AbstractHeapModel})
     * @return a fully initialised {@code AllocatorWrapperModel}
     */
    public static AllocatorWrapperModel run(PointerAnalysisResult preResult,
                                             AnalysisOptions options) {
        ClassHierarchy h = World.get().getClassHierarchy();
        Set<JMethod> wrappers = WrapperDetector.detect(preResult, h);
        return new AllocatorWrapperModel(wrappers, preResult, options);
    }

    // ------------------------------------------------------------------ //
    //  HeapModel API
    // ------------------------------------------------------------------ //

    /**
     * For allocation sites inside wrapper methods the body is suppressed by
     * {@link AllocatorWrapperPlugin}, so this override is only reached for
     * non-wrapper allocations — delegate to the standard per-allocation-site
     * NewObj.
     */
    @Override
    protected Obj doGetObj(New allocSite) {
        return getNewObj(allocSite);
    }

    // ------------------------------------------------------------------ //
    //  Public API for AllocatorWrapperPlugin
    // ------------------------------------------------------------------ //

    public boolean isWrapper(JMethod m) {
        return wrappers.contains(m);
    }

    public Set<JMethod> getWrappers() {
        return Collections.unmodifiableSet(wrappers);
    }

    /**
     * Returns the per-callsite abstract object for {@code callSite}.
     * If {@code callSite} was not present in the pre-analysis call graph
     * (rare, e.g., for edges only discovered during the main analysis),
     * a fresh MockObj is created on the fly using {@code callee}'s return type.
     *
     * @param callSite the {@link Invoke} statement of the wrapper call
     * @param callee   the wrapper method being called (used if a new MockObj
     *                 must be created on the fly)
     * @return the per-callsite MockObj, or {@code null} if {@code callee}'s
     *         return type is not a reference type
     */
    Obj getOrCreateCallSiteObj(Invoke callSite, JMethod callee) {
        return callSiteObjs.computeIfAbsent(callSite, cs -> {
            if (!(callee.getReturnType() instanceof ReferenceType retType)) {
                return null;
            }
            logger.debug("CAFD: creating on-the-fly MockObj for callsite {}", cs);
            return getMockObj(CAFD_DESC, cs, retType, callee, true);
        });
    }

    // ------------------------------------------------------------------ //
    //  Private helpers
    // ------------------------------------------------------------------ //

    private Map<Invoke, Obj> buildCallSiteObjs(Set<JMethod> wrappers,
                                                PointerAnalysisResult preResult) {
        Map<Invoke, Obj> map = new HashMap<>();
        for (JMethod wrapper : wrappers) {
            if (!(wrapper.getReturnType() instanceof ReferenceType retType)) {
                continue;
            }
            for (Invoke callSite : preResult.getCallGraph().getCallersOf(wrapper)) {
                Obj obj = getMockObj(CAFD_DESC, callSite, retType, wrapper, true);
                map.put(callSite, obj);
            }
        }
        return map;
    }
}
