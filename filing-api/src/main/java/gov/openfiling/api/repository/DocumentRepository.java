package gov.openfiling.api.repository;

import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.DocumentVisibility;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @EntityGraph(attributePaths = {"application", "application.owner", "application.examiner", "uploadedBy"})
    Optional<Document> findWithApplicationById(UUID id);

    /** Serializes {@code /complete} and the storage-event callback for the same document. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.storageKey = :key")
    Optional<Document> lockByStorageKey(@Param("key") String key);

    @EntityGraph(attributePaths = "uploadedBy")
    List<Document> findByApplication_IdAndStatusNotOrderByCreatedAtDesc(UUID applicationId, DocumentStatus status);

    List<Document> findByApplication_Id(UUID applicationId);

    List<Document> findByApplication_IdAndStatus(UUID applicationId, DocumentStatus status);

    List<Document> findByApplication_IdAndVisibilityAndStatusOrderByUploadedAtAsc(
            UUID applicationId, DocumentVisibility visibility, DocumentStatus status);

    long countByApplication_IdAndStatusNot(UUID applicationId, DocumentStatus status);

    @Query("""
            select d.application.id as id, count(d) as total from Document d
            where d.application.id in :ids and d.status <> gov.openfiling.api.model.DocumentStatus.EXPIRED
            group by d.application.id""")
    List<IdCountRow> countActiveByApplications(@Param("ids") Collection<UUID> applicationIds);

    boolean existsByApplication_IdAndDocCodeAndStatusAndUploadedAtAfter(
            UUID applicationId, DocumentCode docCode, DocumentStatus status, Instant after);

    boolean existsByApplication_IdAndDocCodeAndStatus(UUID applicationId, DocumentCode docCode, DocumentStatus status);

    @Query("select d from Document d where d.status = :status and d.uploadExpiresAt < :cutoff order by d.uploadExpiresAt")
    List<Document> findStale(@Param("status") DocumentStatus status, @Param("cutoff") Instant cutoff, Pageable pageable);

    long countByStatusNot(DocumentStatus status);

    @Query("select coalesce(sum(d.sizeBytes), 0) from Document d where d.status = gov.openfiling.api.model.DocumentStatus.UPLOADED")
    long sumUploadedBytes();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Document d where d.application.id = :applicationId")
    int deleteByApplicationId(@Param("applicationId") UUID applicationId);
}
