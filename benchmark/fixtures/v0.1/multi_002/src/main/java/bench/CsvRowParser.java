package bench;
import java.util.ArrayList;
import java.util.List;
public final class CsvRowParser {
  public List<String> parse(String row) { String[] parts = row.split(","); List<String> values = new ArrayList<>(); for (int i = 0; i < parts.length - 1; i++) values.add(parts[i]); return values; }
}
