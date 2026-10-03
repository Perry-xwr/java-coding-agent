public class QuotaPolicy {
    public int remaining(int limit, int used) {
        return Math.max(0, limit - used);
    }

    public static void main(String[] args) {
        System.out.println(new QuotaPolicy().remaining(10, 3));
    }
}
