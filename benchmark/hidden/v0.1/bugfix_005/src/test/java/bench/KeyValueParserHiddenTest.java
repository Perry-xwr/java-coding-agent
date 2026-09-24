package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class KeyValueParserHiddenTest { @Test void parsesColonAndTrims() { assertArrayEquals(new String[]{"name","Ada"},new KeyValueParser().parse(" name : Ada ")); } }
