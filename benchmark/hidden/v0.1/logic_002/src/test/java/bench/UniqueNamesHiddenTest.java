package bench; import java.util.List; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class UniqueNamesHiddenTest { @Test void removesDuplicatesInOrder() { assertEquals(List.of("Ada","Bob","Cara"),new UniqueNames().unique(List.of("Ada","Bob","Ada","Cara","Bob"))); } }
