package bench;
public final class DiscountPolicy { public double finalPrice(double amount) { return amount > 100 ? amount * 0.9 : amount; } }
