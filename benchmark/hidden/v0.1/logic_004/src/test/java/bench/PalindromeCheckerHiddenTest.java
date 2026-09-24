package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class PalindromeCheckerHiddenTest { @Test void normalizesInput() { PalindromeChecker p=new PalindromeChecker(); assertTrue(p.isPalindrome("Never odd or even")); assertFalse(p.isPalindrome("Java")); assertFalse(p.isPalindrome(null)); } }
