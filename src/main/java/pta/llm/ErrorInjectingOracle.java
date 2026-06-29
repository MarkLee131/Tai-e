package pta.llm;
import java.util.function.Function;
public class ErrorInjectingOracle implements LlmOracle {
    private final LlmOracle delegate; private final double p;
    private final Function<LlmResponse,String> corrupt; private final long seed;
    public ErrorInjectingOracle(LlmOracle d, double p, Function<LlmResponse,String> corrupt, long seed) {
        this.delegate = d; this.p = p; this.corrupt = corrupt; this.seed = seed;
    }
    @Override public LlmResponse ask(LlmQuery q) {
        LlmResponse r = delegate.ask(q);
        long h = (q.contextId().hashCode() & 0xffffffffL) ^ (seed * 0x9E3779B97F4A7C15L);
        double frac = Math.floorMod(h, 1000L) / 1000.0;
        if (frac < p) return new LlmResponse(corrupt.apply(r), r.fromCache(), r.estCostUsd());
        return r;
    }
}
