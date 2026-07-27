package com.lineaibot.shared;

import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorBody(String detail) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorBody(exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ErrorBody> handleValidation(Exception exception) {
        var bindingResult = exception instanceof MethodArgumentNotValidException method
                ? method.getBindingResult()
                : ((BindException) exception).getBindingResult();
        var detail = bindingResult.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.unprocessableEntity().body(new ErrorBody(detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorBody("Invalid request body"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> handleConflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("The requested resource conflicts with existing data"));
    }
}
