package dev.daiku.web;

import dev.daiku.core.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ApplicationExceptionHandler {
  @ExceptionHandler(ApplicationException.class)
  public ResponseEntity<String> invalid(ApplicationException e) {
    HttpStatus status =
        switch (e.kind()) {
          case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status)
        .contentType(MediaType.TEXT_PLAIN)
        .body(
            e.getMessage()
                + "\n"
                + "Use your browser's back button to return to the form. For a conflict, reload the"
                + " ticket first.");
  }
}
