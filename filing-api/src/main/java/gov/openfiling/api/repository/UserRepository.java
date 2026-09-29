package gov.openfiling.api.repository;

import gov.openfiling.api.model.User;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(UUID id);

    boolean existsByEmail(String email);

    /** Atomic increment so concurrent failures are all counted. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.failedLogins = u.failedLogins + 1, u.updatedAt = :now where u.id = :id")
    int incrementFailedLogins(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update User u set u.lockedUntil = :until, u.failedLogins = 0, u.updatedAt = :now
            where u.id = :id and u.failedLogins >= :max""")
    int lockIfThresholdReached(
            @Param("id") UUID id, @Param("max") int max, @Param("until") Instant until, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update User u set u.failedLogins = 0, u.lockedUntil = null, u.lastLoginAt = :now, u.updatedAt = :now
            where u.id = :id""")
    int recordSuccessfulLogin(@Param("id") UUID id, @Param("now") Instant now);
}
