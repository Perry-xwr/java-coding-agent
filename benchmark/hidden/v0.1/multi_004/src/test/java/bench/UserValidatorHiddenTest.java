package bench; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class UserValidatorHiddenTest { @Test void validatesBoundaryAndEmailParts() { UserValidator v=new UserValidator(); assertTrue(v.valid("a@b",18)); assertFalse(v.valid("@b",18)); assertFalse(v.valid("a@",18)); assertFalse(v.valid("a@b",17)); } }
