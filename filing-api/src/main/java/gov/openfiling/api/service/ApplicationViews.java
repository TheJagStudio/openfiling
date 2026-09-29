package gov.openfiling.api.service;

import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.ApplicationSummary;
import gov.openfiling.api.dto.InventorDto;
import gov.openfiling.api.mapper.AccountMapper;
import gov.openfiling.api.mapper.InventorMapper;
import gov.openfiling.api.model.ApplicationInventor;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.IdCountRow;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds application DTOs for a given viewer (viewer-dependent flags come from {@link WorkflowPolicy}). */
@Component
@RequiredArgsConstructor
public class ApplicationViews {

    private final WorkflowPolicy policy;
    private final AccountMapper accounts;
    private final InventorMapper inventors;
    private final DocumentRepository documents;
    private final Clock clock;

    /** Summaries with document counts loaded in one grouped query. */
    public List<ApplicationSummary> summaries(List<PatentApplication> apps, Viewer viewer) {
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = documents.countActiveByApplications(apps.stream().map(PatentApplication::getId).toList())
                .stream()
                .collect(Collectors.toMap(IdCountRow::getId, IdCountRow::getTotal));
        return apps.stream().map(a -> summary(a, viewer, counts.getOrDefault(a.getId(), 0L))).toList();
    }

    public ApplicationSummary summary(PatentApplication a, Viewer viewer, long documentCount) {
        ApplicationStatus s = a.getStatus();
        return new ApplicationSummary(
                a.getId(),
                a.getApplicationNumber(),
                ApplicationNumbers.display(a.getApplicationNumber()),
                a.getTitle(),
                a.getApplicationType(),
                a.getEntityStatus(),
                s,
                s.getLabel(),
                s.getCategory(),
                a.getFilingDate(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                accounts.toParty(a.getOwner()),
                accounts.toParty(a.getExaminer()),
                a.getArtUnit(),
                documentCount,
                a.isPublished(),
                daysPending(a),
                policy.isActionRequired(a, viewer));
    }

    public ApplicationDetail detail(PatentApplication a, Viewer viewer) {
        long documentCount = documents.countByApplication_IdAndStatusNot(a.getId(), DocumentStatus.EXPIRED);
        ApplicationSummary s = summary(a, viewer, documentCount);
        List<InventorDto> inventorDtos = inventors.toDtos(a.getInventors());
        return new ApplicationDetail(
                s.id(), s.applicationNumber(), s.applicationNumberDisplay(), s.title(), s.applicationType(),
                s.entityStatus(), s.status(), s.statusLabel(), s.statusCategory(), s.filingDate(), s.createdAt(),
                s.updatedAt(), s.owner(), s.examiner(), s.artUnit(), s.documentCount(), s.published(), s.daysPending(),
                s.actionRequired(),
                a.getConfirmationNumber(),
                a.getAbstractText(),
                a.getTechnologyField(),
                a.getAttorneyDocketNumber(),
                firstNamedInventor(a),
                inventorDtos,
                a.getSubmittedAt(),
                a.getSignature(),
                a.getPublicationNumber(),
                a.getPublicationDate(),
                a.getPatentNumber(),
                a.getIssueDate(),
                a.getVersion() == null ? 0 : a.getVersion(),
                policy.permissions(a, viewer),
                policy.availableActions(a, viewer));
    }

    /** Days since filing while pending (filed, non-terminal); {@code null} otherwise. */
    public Integer daysPending(PatentApplication a) {
        if (!a.getStatus().isActive() || a.getFilingDate() == null) {
            return null;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return (int) Math.max(0, ChronoUnit.DAYS.between(a.getFilingDate(), today));
    }

    public static String firstNamedInventor(PatentApplication a) {
        List<ApplicationInventor> list = a.getInventors();
        return list == null || list.isEmpty() ? null : list.getFirst().fullName();
    }
}
