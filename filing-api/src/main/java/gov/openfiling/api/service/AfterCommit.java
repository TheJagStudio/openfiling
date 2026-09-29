package gov.openfiling.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects that cannot be rolled back (object deletion) only once the database change is
 * committed; runs immediately when no transaction is active (unit tests, jobs). Failures are logged,
 * never propagated &mdash; the stale-upload sweep and bucket lifecycle rules clean up leftovers.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(String description, Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(description, action);
                }
            });
        } else {
            safely(description, action);
        }
    }

    private static void safely(String description, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("After-commit action '{}' failed: {}", description, e.getMessage());
        }
    }
}
