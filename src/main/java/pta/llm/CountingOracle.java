package pta.llm;

/**
 * Wraps any {@link LlmOracle} delegate, counting the number of {@link #ask}
 * calls and accumulating the total estimated cost (USD) from
 * {@link LlmResponse#estCostUsd()}. Cache hits have {@code estCostUsd = 0.0};
 * live calls carry a positive cost. Both are counted as queries.
 *
 * <p>Use this to instrument arm oracles inside {@link pta.eval.ConfigRunner}
 * so that {@code llmQueries} and {@code costUsd} can be surfaced per run.
 */
public final class CountingOracle implements LlmOracle {

    private final LlmOracle delegate;
    private long queries = 0;
    private double costUsd = 0.0;

    public CountingOracle(LlmOracle delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse ask(LlmQuery q) {
        LlmResponse resp = delegate.ask(q);
        queries++;
        costUsd += resp.estCostUsd();
        return resp;
    }

    /** Number of {@link #ask} calls made so far. */
    public long queryCount() {
        return queries;
    }

    /** Sum of {@link LlmResponse#estCostUsd()} from all {@link #ask} calls so far. */
    public double totalCostUsd() {
        return costUsd;
    }
}
