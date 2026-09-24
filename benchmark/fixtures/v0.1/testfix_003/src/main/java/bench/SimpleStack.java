package bench;
import java.util.ArrayList;
import java.util.List;
public final class SimpleStack { private final List<String> values = new ArrayList<>(); public void push(String value) { values.add(value); } public int size() { return values.size(); } }
