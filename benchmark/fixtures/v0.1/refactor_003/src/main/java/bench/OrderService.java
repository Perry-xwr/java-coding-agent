package bench;
public final class OrderService {
  public String reserve(String item) { if (item == null || item.isBlank()) throw new IllegalArgumentException("item"); return "reserved:" + item; }
  public String ship(String item) { if (item == null || item.isBlank()) throw new IllegalArgumentException("item"); return "shipped:" + item; }
}
