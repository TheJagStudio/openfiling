package gov.openfiling.api.repository;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.PatentApplication;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<PatentApplication, UUID>, JpaSpecificationExecutor<PatentApplication> {

    /** Owner and examiner fetched in the same query (both are needed for every visibility check and DTO). */
    @EntityGraph(attributePaths = {"owner", "examiner"})
    Optional<PatentApplication> findWithPartiesById(UUID id);

    @EntityGraph(attributePaths = {"owner", "examiner"})
    Optional<PatentApplication> findByApplicationNumber(String applicationNumber);

    @Override
    @EntityGraph(attributePaths = {"owner", "examiner"})
    Page<PatentApplication> findAll(@Nullable Specification<PatentApplication> spec, Pageable pageable);

    @Query("select a.status as status, count(a) as total from PatentApplication a where a.owner.id = :ownerId group by a.status")
    List<StatusCountRow> countByStatusForOwner(@Param("ownerId") UUID ownerId);

    @Query("""
            select a.status as status, count(a) as total from PatentApplication a
            where a.status <> gov.openfiling.api.model.ApplicationStatus.DRAFT group by a.status""")
    List<StatusCountRow> countFiledByStatus();

    @Query("select a.owner.id as id, count(a) as total from PatentApplication a where a.owner.id in :ownerIds group by a.owner.id")
    List<IdCountRow> countByOwners(@Param("ownerIds") Collection<UUID> ownerIds);

    long countByExaminerIsNullAndStatusIn(Collection<ApplicationStatus> statuses);

    long countByExaminer_IdAndStatusIn(UUID examinerId, Collection<ApplicationStatus> statuses);

    long countByStatusNot(ApplicationStatus status);

    long countByStatus(ApplicationStatus status);

    long countByPublishedTrue();

    /** Average days since filing of pending (filed, non-terminal) applications. */
    @Query(value = """
            select avg(cast(:today as date) - filing_date) from filing.applications
            where status not in ('DRAFT', 'ISSUED', 'ABANDONED') and filing_date is not null""", nativeQuery = true)
    Double averagePendencyDays(@Param("today") LocalDate today);

    @Query(value = "select nextval('filing.application_serial_seq')", nativeQuery = true)
    long nextApplicationSerial();

    @Query(value = "select nextval('filing.publication_serial_seq')", nativeQuery = true)
    long nextPublicationSerial();

    @Query(value = "select nextval('filing.patent_serial_seq')", nativeQuery = true)
    long nextPatentSerial();
}
