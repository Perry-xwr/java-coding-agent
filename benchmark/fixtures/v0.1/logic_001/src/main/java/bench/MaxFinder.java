package bench;
public final class MaxFinder {
  public int max(int[] values) { int max = 0; for (int value : values) if (value > max) max = value; return max; }
}
