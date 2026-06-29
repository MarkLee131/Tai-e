package pta.llm;
import java.util.Map;
public class MockOracle implements LlmOracle {
    private final Map<String,String> answers; private final String def;
    public MockOracle(Map<String,String> answers, String def) { this.answers = answers; this.def = def; }
    @Override public LlmResponse ask(LlmQuery q) {
        return new LlmResponse(answers.getOrDefault(q.contextId(), def), true, 0.0);
    }
}
