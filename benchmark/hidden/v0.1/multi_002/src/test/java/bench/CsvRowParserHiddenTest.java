package bench; import java.util.List; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class CsvRowParserHiddenTest { @Test void trimsAndKeepsLastColumn() { assertEquals(List.of("Ada","42","Paris"),new CsvRowParser().parse(" Ada , 42 , Paris ")); } }
