package com.dcbate.tradingplatform.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Central mapping from domain exceptions to HTTP status codes, shared by every controller. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({OrderNotFoundException.class, PaymentNotFoundException.class, AccountNotFoundException.class,
            TransferNotFoundException.class, LoanNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, List.of(e.getMessage()), request);
    }

    @ExceptionHandler({InvalidPaymentStateException.class, InsufficientFundsException.class, AccountNotActiveException.class,
            CurrencyMismatchException.class, RateUnavailableException.class, LoanNotActiveException.class,
            EmailAlreadyRegisteredException.class, InvalidAccountClosureException.class, InsufficientPositionException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, List.of(e.getMessage()), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, List.of("Access denied"), request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    public ResponseEntity<ApiError> handleUnauthorized(RuntimeException e, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, List.of(e.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<String> messages = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, messages, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, List.of("An unexpected error occurred"), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, List<String> messages, HttpServletRequest request) {
        ApiError error = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), messages, request.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }
}
