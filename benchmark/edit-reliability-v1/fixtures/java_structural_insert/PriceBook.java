class PriceBook {
    int total(int[] prices) {
        int sum = 0;
        for (int price : prices) sum += price;
        return sum;
    }
}
