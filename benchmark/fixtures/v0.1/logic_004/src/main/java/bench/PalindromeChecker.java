package bench;
public final class PalindromeChecker {
  public boolean isPalindrome(String value) { if (value == null) return false; return new StringBuilder(value).reverse().toString().equals(value); }
}
