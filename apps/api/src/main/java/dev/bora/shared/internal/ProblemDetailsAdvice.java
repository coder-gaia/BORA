package dev.bora.shared.internal;

import dev.bora.shared.ApiException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ProblemDetailsAdvice {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> apiException(ApiException failure) {
    HttpHeaders headers = new HttpHeaders();
    if (failure.retryAfterSeconds() != null) {
      headers.set(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds().toString());
    }
    return new ResponseEntity<>(
        problem(failure.status(), failure.code(), failure.getMessage()), headers, failure.status());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException failure) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Confira os dados informados.");
    problem.setProperty(
        "errors",
        failure.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
            .toList());
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    ConstraintViolationException.class
  })
  ResponseEntity<ProblemDetail> malformedRequest(Exception failure) {
    return ResponseEntity.badRequest()
        .body(
            problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Confira o formato da requisição."));
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    ProblemDetail result = ProblemDetail.forStatusAndDetail(status, message);
    result.setTitle("Não foi possível concluir a solicitação.");
    result.setType(URI.create("urn:bora:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    result.setProperty("code", code);
    return result;
  }

  private record FieldError(String field, String message) {}
}
