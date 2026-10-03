public class Parser {
    public String normalize(String input) {
        if (input == null) return "";
        return input.replaceAll(",+$", "");
    }
}
