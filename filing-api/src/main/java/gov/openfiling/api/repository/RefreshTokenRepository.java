package gov.openfiling.api.repository;

import gov.openfiling.api.model.RefreshToken;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Row lock so two concurrent refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> lockByTokenHash(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    @Query("""
            select t from RefreshToken t
            where t.user.id = :userId and t.revokedAt is null and t.expiresAt > :now
            order by t.lastUsedAt desc nulls last, t.createdAt desc""")
    List<RefreshToken> findActiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("""
            select t.familyId as id, min(t.createdAt) as startedAt from RefreshToken t
            where t.familyId in :families group by t.familyId""")
    List<FamilyStartRow> familyStarts(@Param("families") Collection<UUID> families);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.familyId = :familyId and t.user.id = :userId and t.revokedAt is null""")
    int revokeFamilyOfUser(@Param("userId") UUID userId, @Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("now") Instant now);

    boolean existsByFamilyIdAndUser_Id(UUID familyId, UUID userId);

    interface FamilyStartRow {

        UUID getId();

        Instant getStartedAt();
    }
}
