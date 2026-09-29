package gov.openfiling.api.repository;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.StatusEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatusEventRepository extends JpaRepository<StatusEvent, Long> {

    @EntityGraph(attributePaths = "actor")
    List<StatusEvent> findByApplicationIdOrderByCreatedAtAscIdAsc(UUID applicationId);

    /** The most recent transition into {@code status} (e.g. when the current rejection was mailed). */
    Optional<StatusEvent> findFirstByApplicationIdAndToStatusOrderByCreatedAtDescIdDesc(UUID applicationId, ApplicationStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StatusEvent e where e.applicationId = :applicationId")
    int deleteByApplicationId(@Param("applicationId") UUID applicationId);
}
