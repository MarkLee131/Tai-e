package pta.llm;

import java.util.List;
import java.util.function.Supplier;

/**
 * Adversarial-oracle decorator (RQ-adv): deterministically corrupts a fraction of the
 * delegate's responses so the paper's ∀-oracle theorems can be instantiated
 * experimentally. Corruption is applied AFTER the delegate answers, so warm-cache
 * sweeps issue no live queries. The per-query decision hash matches
 * {@link ErrorInjectingOracle} (contextId ^ seed), keeping sweep points reproducible.
 *
 * Modes: silent (empty answer) | random (unloadable garbage) | {@code fixed:<fqn>}
 * | loadable (deterministic pick from the classpath pool) | truncate (first line).
 * Site filters restrict corruption to contextIds containing (onlySite) /
 * not containing (exceptSite) a substring — used for per-site blast-radius runs.
 */
public class CorruptingOracle implements LlmOracle {

    private final LlmOracle delegate;
    private final String mode;
    private final double rate;
    private final long seed;
    private final String onlySite;
    private final String exceptSite;
    private final Supplier<List<String>> loadablePool;

    public CorruptingOracle(LlmOracle delegate, String mode, double rate, long seed,
                            String onlySite, String exceptSite,
                            Supplier<List<String>> loadablePool) {
        this.delegate = delegate;
        this.mode = mode;
        this.rate = rate;
        this.seed = seed;
        this.onlySite = onlySite;
        this.exceptSite = exceptSite;
        this.loadablePool = loadablePool;
    }

    public static LlmOracle wrapIfConfigured(LlmOracle base, Supplier<List<String>> loadablePool) {
        String mode = System.getProperty("arm2.corrupt");
        if (mode == null || base == null) {
            return base;
        }
        return new CorruptingOracle(base, mode,
                Double.parseDouble(System.getProperty("arm2.corruptRate", "1.0")),
                Long.parseLong(System.getProperty("arm2.corruptSeed", "1")),
                System.getProperty("arm2.corruptSite"),
                System.getProperty("arm2.corruptExceptSite"),
                loadablePool);
    }

    @Override
    public LlmResponse ask(LlmQuery q) {
        LlmResponse r = delegate.ask(q);
        if (!selected(q.contextId()) || !hit(q.contextId())) {
            return r;
        }
        return new LlmResponse(corrupt(q, r), r.fromCache(), r.estCostUsd());
    }

    private boolean selected(String ctx) {
        if (onlySite != null && !ctx.contains(onlySite)) {
            return false;
        }
        return exceptSite == null || !ctx.contains(exceptSite);
    }

    private boolean hit(String ctx) {
        long h = (ctx.hashCode() & 0xffffffffL) ^ (seed * 0x9E3779B97F4A7C15L);
        return Math.floorMod(h, 1000L) / 1000.0 < rate;
    }

    private String corrupt(LlmQuery q, LlmResponse r) {
        switch (mode) {
            case "silent":
                return "";
            case "truncate":
                return r.asLines().isEmpty() ? "" : r.asLines().get(0);
            case "random": {
                long h = (q.contextId().hashCode() & 0xffffffffL)
                        ^ ((seed + 1) * 0x9E3779B97F4A7C15L);
                return "adv.gen.C" + Math.floorMod(h, 1_000_000L);
            }
            case "loadable": {
                List<String> pool = loadablePool.get();
                if (pool.isEmpty()) {
                    return "";
                }
                long h = (q.contextId().hashCode() & 0xffffffffL)
                        ^ ((seed + 1) * 0x9E3779B97F4A7C15L);
                return pool.get((int) Math.floorMod(h, pool.size()));
            }
            default:
                if (mode.startsWith("fixed:")) {
                    return mode.substring("fixed:".length());
                }
                throw new IllegalArgumentException("unknown corruption mode: " + mode);
        }
    }
}
