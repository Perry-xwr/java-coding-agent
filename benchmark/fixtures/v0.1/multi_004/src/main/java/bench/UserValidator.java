package bench;
public final class UserValidator {
  public boolean valid(String email, int age) { return email != null && email.contains("@") && age > 18; }
}
