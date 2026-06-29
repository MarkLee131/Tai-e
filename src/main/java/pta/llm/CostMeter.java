package pta.llm;
public class CostMeter {
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String m) { super(m); }
    }
    private final double capUsd, inRate, outRate;
    private double spent = 0.0;
    public CostMeter(double capUsd, double usdPerInputTok, double usdPerOutputTok) {
        this.capUsd = capUsd; this.inRate = usdPerInputTok; this.outRate = usdPerOutputTok;
    }
    public double estimate(String prompt, String response) {
        long inTok = (prompt.length() + 3) / 4, outTok = (response.length() + 3) / 4;
        return inTok * inRate + outTok * outRate;
    }
    public double spent() { return spent; }
    public boolean wouldExceed(double usd) { return spent + usd > capUsd + 1e-12; }
    public void charge(double usd) {
        if (wouldExceed(usd))
            throw new BudgetExceededException("LLM budget cap $" + capUsd + " would be exceeded (spent $" + spent + ")");
        spent += usd;
    }
}
