package gov.openfiling.api.exception;

import java.time.Duration;

import lombok.Getter;

/** Too many failed logins; the account is locked for {@link #getRetryAfter()} (429 + Retry-After). */
@Getter
public class AccountLockedException extends RuntimeException {

    private final Duration retryAfter;

    public AccountLockedException(Duration retryAfter) {
        super("Too many failed login attempts. Try again in " + Math.max(1, (retryAfter.toSeconds() + 59) / 60) + " minute(s).");
        this.retryAfter = retryAfter;
    }
}
