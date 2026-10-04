public class RouteTable {
    public String route(boolean matches) {
        if (matches) {
            return "matched";
        } else {
            return "fallback";
        }
    }
}
