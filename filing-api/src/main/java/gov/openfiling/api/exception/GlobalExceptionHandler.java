package gov.openfiling.api.exception;

import gov.openfiling.api.config.RequestIdFilter;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to RFC 7807 {@code application/problem+json} with {@code requestId},
 * {@code instance} and (for validation / business-rule failures) {@code errors: [{field, message}]}.
 * Never leaks stack traces.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), request);
        pd.setProperty("errors", ex.getErrors());
        return pd;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail invalidRefresh(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ProblemDetail> locked(AccountLockedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ProblemDetail upstream(UpstreamUnavailableException ex, HttpServletRequest request) {
        log.warn("Upstream unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldProblem> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(Comparator.comparing(FieldProblem::field))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", request);
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataConflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "The request conflicts with existing data", request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    ProblemDetail optimisticLock(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "The resource was modified concurrently; reload it and retry", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to access this resource", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication is required", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.add(toFieldProblem(fe)));
        ex.getBindingResult().getGlobalErrors().forEach(ge -> errors.add(new FieldProblem(ge.getObjectName(), ge.getDefaultMessage())));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String param = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                if (error instanceof FieldError fe) {
                    errors.add(toFieldProblem(fe));
                } else {
                    errors.add(new FieldProblem(param, error.getDefaultMessage()));
                }
            }
        });
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail pd = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(statusCode);
        if (request instanceof ServletWebRequest swr) {
            enrich(pd, swr.getRequest());
        }
        if (statusCode.is4xxClientError() && pd.getDetail() != null && pd.getDetail().startsWith("Failed to read request")) {
            pd.setDetail("Malformed request body");
        }
        return super.handleExceptionInternal(ex, pd, headers, statusCode, request);
    }

    private static FieldProblem toFieldProblem(FieldError fe) {
        String message = fe.isBindingFailure()
                ? "invalid value '" + fe.getRejectedValue() + "'"
                : fe.getDefaultMessage();
        return new FieldProblem(fe.getField(), message);
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    static ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(pd, request);
        return pd;
    }

    private static void enrich(ProblemDetail pd, HttpServletRequest request) {
        if (pd.getType() == null) {
            pd.setType(ABOUT_BLANK);
        }
        if (pd.getInstance() == null) {
            pd.setInstance(URI.create(request.getRequestURI()));
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
    }
}
