package com.rondinella.moneymanageapi.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Errors the web app can show, without what is inside them: the message of an unexpected exception can carry
 * the user's data (an amount, a description, a SQL detail), so it never reaches the response or the log.
 * Same body as Spring Boot's errors ({@code status}, {@code error}, {@code message}). The errors Spring already
 * knows (ResponseStatusException with our own messages, bad parameters, login and access) go on as before.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String, Object>> notFound() {
    return body(HttpStatus.NOT_FOUND, "Not found");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> conflict() {
    return body(HttpStatus.CONFLICT, "The data clashes with what is already there");
  }

  /** a parameter that is not what the endpoint takes (e.g. a date that is not one): the caller's mistake */
  @ExceptionHandler(TypeMismatchException.class)
  ResponseEntity<Map<String, Object>> badParameter() {
    return body(HttpStatus.BAD_REQUEST, "Bad parameter");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> tooLarge() {
    return body(HttpStatus.CONTENT_TOO_LARGE, "File too large");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) throws Exception {
    if (e instanceof ErrorResponse || e instanceof AuthenticationException || e instanceof AccessDeniedException) {
      throw e; // handled by Spring (same exception: it goes on to the next resolver)
    }
    // the endpoint pattern, not the path: the path can hold an account name or an amount
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    log.error("Unexpected {} on {} {} at {}", e.getClass().getName(), request.getMethod(), pattern, where(e));
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
  }

  /** First frames of our code: enough to find the bug, no message. */
  private static String where(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String frames = Arrays.stream(root.getStackTrace())
        .filter(f -> f.getClassName().startsWith("com.rondinella."))
        .limit(3)
        .map(StackTraceElement::toString)
        .reduce((a, b) -> a + " < " + b)
        .orElse("?");
    return frames + (root != e ? " (cause " + root.getClass().getName() + ")" : "");
  }

  private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", message);
    return ResponseEntity.status(status).body(body);
  }
}
