package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class TokenMatcherHiddenTest { @Test void comparesValuesAndNulls() { TokenMatcher m=new TokenMatcher(); assertTrue(m.matches(new String("x"),new String("x"))); assertTrue(m.matches(null,null)); assertFalse(m.matches("x",null)); } }
