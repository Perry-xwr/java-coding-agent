package bench;
import java.util.LinkedHashMap;
import java.util.Map;
public final class Inventory {
  private final Map<String,Integer> quantities = new LinkedHashMap<>(); private int total;
  public void add(String sku, int quantity) { quantities.merge(sku, quantity, Integer::sum); total++; }
  public int quantity(String sku) { return quantities.getOrDefault(sku, 0); } public int totalUnits() { return total; }
}
