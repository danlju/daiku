package dev.daiku.core;

/** An application failure whose transport-specific response is chosen by the adapter. */
public class ApplicationException extends RuntimeException {
  public enum Kind {
    INVALID_INPUT,
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;

  public ApplicationException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
