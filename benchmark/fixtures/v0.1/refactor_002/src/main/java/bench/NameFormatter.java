package bench;
public final class NameFormatter {
  public boolean same(String left, String right) { return left.trim().toLowerCase().equals(right.trim().toLowerCase()); }
  public String key(String name) { return name.trim().toLowerCase(); }
}
