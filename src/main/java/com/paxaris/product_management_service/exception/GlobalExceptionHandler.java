package com.paxaris.product_management_service.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import static com.paxaris.product_management_service.config.CorrelationIdFilter.CORRELATION_ID_HEADER;
import static com.paxaris.product_management_service.config.CorrelationIdFilter.CORRELATION_ID_KEY;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<ErrorResponse> handleMethodNotSupported(
                        HttpRequestMethodNotSupportedException ex,
                        HttpServletRequest request) {

                String correlationId = resolveCorrelationId(request);
                log.warn("MethodNotSupported correlationId={} path={} message={}",
                        correlationId, request.getRequestURI(), ex.getMessage());

                ErrorResponse error = new ErrorResponse(
                                LocalDateTime.now(),
                                HttpStatus.METHOD_NOT_ALLOWED.value(),
                                "Method Not Allowed",
                                ex.getMessage(),
                                request.getRequestURI(),
                                correlationId
                );

                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
        }

        @ExceptionHandler({
                MethodArgumentNotValidException.class,
                MethodArgumentTypeMismatchException.class,
                HttpMessageNotReadableException.class,
                IllegalArgumentException.class
        })
        public ResponseEntity<ErrorResponse> handleBadRequest(
                        Exception ex,
                        HttpServletRequest request) {

                String correlationId = resolveCorrelationId(request);
                log.warn("BadRequest correlationId={} path={} message={}",
                        correlationId, request.getRequestURI(), ex.getMessage());

                String message;
                if (ex instanceof MethodArgumentNotValidException validationEx) {
                        message = validationEx.getBindingResult().getFieldErrors().stream()
                                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                                .collect(Collectors.joining("; "));
                } else {
                        message = ex.getMessage();
                }

                ErrorResponse error = new ErrorResponse(
                                LocalDateTime.now(),
                                HttpStatus.BAD_REQUEST.value(),
                                "Bad Request",
                                message,
                                request.getRequestURI(),
                                correlationId
                );

                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<ErrorResponse> handleResponseStatus(
                        ResponseStatusException ex,
                        HttpServletRequest request) {

                String correlationId = resolveCorrelationId(request);
                HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
                log.warn("ResponseStatusException correlationId={} path={} status={} message={}",
                        correlationId, request.getRequestURI(), status.value(), ex.getMessage());

                ErrorResponse error = new ErrorResponse(
                                LocalDateTime.now(),
                                status.value(),
                                status.getReasonPhrase(),
                                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                                request.getRequestURI(),
                                correlationId
                );

                return ResponseEntity.status(status).body(error);
        }

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
                        MaxUploadSizeExceededException ex,
                        HttpServletRequest request) {

                String correlationId = resolveCorrelationId(request);
                log.warn("MaxUploadSizeExceeded correlationId={} path={} message={}",
                        correlationId, request.getRequestURI(), ex.getMessage());

                ErrorResponse error = new ErrorResponse(
                                LocalDateTime.now(),
                                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                                "Payload Too Large",
                                "Uploaded ZIP exceeds configured size limit. Increase multipart limits or reduce ZIP size.",
                                request.getRequestURI(),
                                correlationId
                );

                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
        }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("AccessDeniedException correlationId={} path={} message={}",
                correlationId, request.getRequestURI(), ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.FORBIDDEN.value(),
                "Access Denied",
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFound(
            RoleNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("RoleNotFoundException correlationId={} path={} message={}",
                correlationId, request.getRequestURI(), ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Role Not Found",
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.error("UnhandledException correlationId={} path={} message={}",
                correlationId, request.getRequestURI(), ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(correlationId)) {
            return correlationId;
        }

        Object correlationAttr = request.getAttribute(CORRELATION_ID_KEY);
        if (correlationAttr != null && StringUtils.hasText(correlationAttr.toString())) {
            return correlationAttr.toString();
        }

        return "N/A";
    }
}
