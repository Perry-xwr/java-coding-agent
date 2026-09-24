package bench;
public final class PriceCalculator {
  public double retail(double base) { return base + base * 0.20; }
  public double wholesale(double base) { return base + base * 0.05; }
}
