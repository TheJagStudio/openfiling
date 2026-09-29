package gov.openfiling.api.repository;

import gov.openfiling.api.model.Redaction;
import gov.openfiling.api.model.RedactionStatus;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedactionRepository extends JpaRepository<Redaction, UUID> {

    long countByStatus(RedactionStatus status);

    @Query("select coalesce(sum(r.redactionCount), 0) from Redaction r where r.status = gov.openfiling.api.model.RedactionStatus.COMPLETED")
    long sumCompletedRedactions();

    /** Redactions per category across completed documents (jsonb {@code [{category,count}]}). */
    @Query(value = """
            select c ->> 'category' as category, sum(cast(c ->> 'count' as bigint)) as total
            from filing.redactions r cross join lateral jsonb_array_elements(r.categories) c
            where r.status = 'COMPLETED'
            group by c ->> 'category' order by total desc, category""", nativeQuery = true)
    List<CategoryTotalRow> totalsByCategory();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Redaction r where r.documentId in :ids")
    int deleteByDocumentIds(@Param("ids") Collection<UUID> documentIds);

    interface CategoryTotalRow {

        String getCategory();

        long getTotal();
    }
}
