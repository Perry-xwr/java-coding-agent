package bench;
public final class KeyValueParser {
  public String[] parse(String input) { String[] parts = input.split(",", 2); return new String[]{parts[0].trim(), parts[1].trim()}; }
}
