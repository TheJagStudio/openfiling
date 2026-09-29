package gov.openfiling.api.seed;

import gov.openfiling.api.model.ApplicationInventor;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.model.WorkflowAction;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.StatusEventRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.service.ApplicationNumbers;
import gov.openfiling.api.service.AuthService;
import gov.openfiling.api.service.HistoryService;
import gov.openfiling.api.service.NotificationService;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dev seed ({@code app.seed.enabled=true}, on in profile {@code dev}): the three demo users and a
 * dozen applications for the inventor across the whole status lifecycle, with backdated prosecution
 * history, examiner assignment, pre-grant publications and one issued patent. No files (documents
 * are uploaded through the UI). Idempotent: skipped when the demo inventor already exists.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevSeedRunner implements ApplicationRunner {

    static final String ADMIN_EMAIL = "admin@openfiling.local";
    static final String EXAMINER_EMAIL = "examiner@openfiling.local";
    static final String INVENTOR_EMAIL = "inventor@openfiling.local";

    private final UserRepository users;
    private final ApplicationRepository applications;
    private final StatusEventRepository statusEvents;
    private final AuthService auth;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    record Demo(String title, ApplicationType type, EntityStatus entity, ApplicationStatus target, int filedDaysAgo,
            boolean published, String field, String abstractText, String[][] inventors) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(INVENTOR_EMAIL)) {
            log.info("Dev seed skipped (demo users already exist)");
            return;
        }
        tx.executeWithoutResult(status -> seed());
    }

    void seed() {
        // Dev-only demo credentials, documented in the README; never logged.
        User admin = auth.createUser(ADMIN_EMAIL, "Admin#12345", "Ada Admin", EnumSet.of(Role.ADMIN, Role.EXAMINER), "2100");
        User examiner = auth.createUser(EXAMINER_EMAIL, "Examiner#12345", "Grace Examiner", EnumSet.of(Role.EXAMINER), "2100");
        User inventor = auth.createUser(INVENTOR_EMAIL, "Inventor#12345", "Ada Inventor", EnumSet.of(Role.INVENTOR), null);
        int count = 0;
        for (Demo demo : demos()) {
            createApplication(demo, inventor, examiner);
            count++;
        }
        log.info("Dev seed created 3 users ({}, {}, {}) and {} applications", admin.getEmail(), examiner.getEmail(),
                inventor.getEmail(), count);
    }

    private void createApplication(Demo demo, User owner, User examiner) {
        Instant now = clock.instant();
        Instant created = now.minus(Duration.ofDays(demo.filedDaysAgo() + 6L));
        PatentApplication app = new PatentApplication();
        app.setId(UUID.randomUUID());
        app.setTitle(demo.title());
        app.setApplicationType(demo.type());
        app.setEntityStatus(demo.entity());
        app.setTechnologyField(demo.field());
        app.setAbstractText(demo.abstractText());
        app.setAttorneyDocketNumber("OF-" + (1000 + Math.abs(demo.title().hashCode() % 9000)));
        app.setOwner(owner);
        app.setStatus(ApplicationStatus.DRAFT);
        List<ApplicationInventor> inventors = new ArrayList<>();
        for (String[] person : demo.inventors()) {
            ApplicationInventor i = new ApplicationInventor();
            i.setGivenName(person[0]);
            i.setFamilyName(person[1]);
            i.setCity(person[2]);
            i.setState(person[3]);
            i.setCountry(person[4]);
            i.setCitizenship(person[4]);
            inventors.add(i);
        }
        app.replaceInventors(inventors);

        List<Step> steps = steps(demo);
        LocalDate filingDate = LocalDate.ofInstant(now.minus(Duration.ofDays(demo.filedDaysAgo())), ZoneOffset.UTC);
        if (demo.target().isFiled()) {
            app.setApplicationNumber(ApplicationNumbers.applicationNumber(demo.type(), applications.nextApplicationSerial()));
            app.setConfirmationNumber(String.format("%04d", Math.abs(demo.title().hashCode()) % 10_000));
            app.setFilingDate(filingDate);
            app.setSubmittedAt(filingDate.atTime(14, 30).toInstant(ZoneOffset.UTC));
            app.setSignature("/" + owner.getDisplayName() + "/");
        }
        boolean examined = steps.stream().anyMatch(s -> s.action() == WorkflowAction.DOCKET);
        if (examined) {
            app.setExaminer(examiner);
            app.setArtUnit(examiner.getArtUnit());
        }
        app.setStatus(demo.target());
        applications.saveAndFlush(app);

        // Replay the history with timestamps spread between filing and now.
        List<StatusEvent> events = new ArrayList<>();
        events.add(event(app, StatusEvent.CREATED, null, ApplicationStatus.DRAFT, "Application created (draft)", null, owner,
                HistoryService.ROLE_INVENTOR, created));
        Instant cursor = app.getSubmittedAt() != null ? app.getSubmittedAt() : created.plus(Duration.ofHours(2));
        long gapHours = steps.isEmpty() ? 0 : Math.max(6, Duration.between(cursor, now).toHours() / (steps.size() + 1));
        ApplicationStatus current = ApplicationStatus.DRAFT;
        for (Step step : steps) {
            ApplicationStatus to = step.to();
            String code = step.action() == null ? StatusEvent.SUBMIT : step.action().name();
            User actor = step.byExaminer() ? examiner : owner;
            String role = step.byExaminer() ? HistoryService.ROLE_EXAMINER : HistoryService.ROLE_INVENTOR;
            events.add(event(app, code, current, to, step.description(app, examiner), step.note(), actor, role, cursor));
            if (to != null) {
                current = to;
            }
            cursor = cursor.plus(Duration.ofHours(gapHours));
        }
        statusEvents.saveAll(events);
        applications.saveAndFlush(app); // publication / patent numbers assigned while replaying
        Instant lastEvent = events.getLast().getCreatedAt();
        jdbc.update("UPDATE filing.applications SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(created), Timestamp.from(lastEvent), app.getId());

        if (demo.target().getCategory() == gov.openfiling.api.model.StatusCategory.ACTION_REQUIRED) {
            String label = NotificationService.label(app);
            notifications.notify(owner.getId(), app, events.getLast().getEventCode(), label + ": " + demo.target().getLabel(),
                    demo.target().getDescription() + " — " + app.getTitle());
        }
    }

    record Step(WorkflowAction action, ApplicationStatus to, boolean byExaminer, String note) {

        String description(PatentApplication app, User examiner) {
            if (action == null) {
                return "Application filed — " + ApplicationStatus.SUBMITTED.getLabel();
            }
            return switch (action) {
                case CLAIM -> "Assigned to examiner " + examiner.getDisplayName();
                case DOCKET -> ApplicationStatus.DOCKETED.getLabel() + " (art unit " + examiner.getArtUnit() + ")";
                case PUBLISH -> "Pre-grant publication " + app.getPublicationNumber();
                case ISSUE_PATENT -> "Patent " + app.getPatentNumber() + " issued";
                case ABANDON -> ApplicationStatus.ABANDONED.getLabel() + " (express abandonment by the applicant)";
                default -> to == null ? action.getLabel() : to.getLabel();
            };
        }
    }

    private List<Step> steps(Demo demo) {
        List<Step> steps = new ArrayList<>();
        ApplicationStatus target = demo.target();
        if (target == ApplicationStatus.DRAFT) {
            return steps;
        }
        steps.add(new Step(null, ApplicationStatus.SUBMITTED, false, null));
        if (target == ApplicationStatus.SUBMITTED) {
            return steps;
        }
        if (target == ApplicationStatus.INCOMPLETE) {
            steps.add(new Step(WorkflowAction.NOTICE_MISSING_PARTS, ApplicationStatus.INCOMPLETE, true,
                    "The application lacks an inventor's oath or declaration (37 CFR 1.63). File it within 2 months."));
            return steps;
        }
        steps.add(new Step(WorkflowAction.START_PRE_EXAM, ApplicationStatus.PRE_EXAM, true, null));
        if (target == ApplicationStatus.PRE_EXAM) {
            return steps;
        }
        steps.add(new Step(WorkflowAction.CLAIM, null, true, null));
        steps.add(new Step(WorkflowAction.DOCKET, ApplicationStatus.DOCKETED, true, null));
        if (demo.published()) {
            steps.add(new Step(WorkflowAction.PUBLISH, null, true, null));
        }
        if (target == ApplicationStatus.DOCKETED) {
            return steps;
        }
        steps.add(new Step(WorkflowAction.START_EXAMINATION, ApplicationStatus.UNDER_EXAMINATION, true, null));
        if (target == ApplicationStatus.UNDER_EXAMINATION) {
            return steps;
        }
        if (target == ApplicationStatus.ALLOWED || target == ApplicationStatus.ISSUED) {
            steps.add(new Step(WorkflowAction.ALLOW, ApplicationStatus.ALLOWED, true, null));
            if (target == ApplicationStatus.ISSUED) {
                steps.add(new Step(WorkflowAction.ISSUE_PATENT, ApplicationStatus.ISSUED, true, null));
            }
            return steps;
        }
        steps.add(new Step(WorkflowAction.ISSUE_NON_FINAL_REJECTION, ApplicationStatus.NON_FINAL_REJECTION, true,
                "Claims 1-8 are rejected under 35 U.S.C. 103 as obvious over Smith (US 9,876,543) in view of Lee (US 2019/0123456)."));
        if (target == ApplicationStatus.NON_FINAL_REJECTION) {
            return steps;
        }
        if (target == ApplicationStatus.ABANDONED) {
            steps.add(new Step(WorkflowAction.ABANDON, ApplicationStatus.ABANDONED, false,
                    "Applicant expressly abandons the application in favour of a continuation-in-part."));
            return steps;
        }
        steps.add(new Step(WorkflowAction.FILE_RESPONSE, ApplicationStatus.RESPONSE_FILED, false,
                "Claims 1 and 5 amended to recite the ceramic interlayer thickness; arguments traverse the combination."));
        if (target == ApplicationStatus.RESPONSE_FILED) {
            return steps;
        }
        steps.add(new Step(WorkflowAction.START_EXAMINATION, ApplicationStatus.UNDER_EXAMINATION, true, null));
        steps.add(new Step(WorkflowAction.ISSUE_FINAL_REJECTION, ApplicationStatus.FINAL_REJECTION, true,
                "The amendment does not overcome Smith; the rejection of claims 1-8 is made final."));
        return steps;
    }

    private StatusEvent event(PatentApplication app, String code, ApplicationStatus from, ApplicationStatus to, String description,
            String note, User actor, String role, Instant at) {
        if (WorkflowAction.PUBLISH.name().equals(code)) {
            LocalDate date = LocalDate.ofInstant(at, ZoneOffset.UTC);
            app.setPublished(true);
            app.setPublicationNumber(ApplicationNumbers.publicationNumber(date, applications.nextPublicationSerial()));
            app.setPublicationDate(date);
            description = "Pre-grant publication " + app.getPublicationNumber();
        } else if (WorkflowAction.ISSUE_PATENT.name().equals(code)) {
            app.setPatentNumber(ApplicationNumbers.patentNumber(applications.nextPatentSerial(), app.isPublished()));
            app.setIssueDate(LocalDate.ofInstant(at, ZoneOffset.UTC));
            app.setPublished(true);
            description = "Patent " + app.getPatentNumber() + " issued";
        }
        StatusEvent e = new StatusEvent();
        e.setApplicationId(app.getId());
        e.setEventCode(code);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setDescription(description);
        e.setNote(note);
        e.setActor(actor);
        e.setActorRole(role);
        e.setCreatedAt(at);
        return e;
    }

    static List<Demo> demos() {
        String[][] ada = {{"Ada", "Lovelace", "Alexandria", "VA", "US"}};
        String[][] duo = {{"Ada", "Lovelace", "Alexandria", "VA", "US"}, {"Kenji", "Watanabe", "Austin", "TX", "JP"}};
        String[][] trio = {{"Ada", "Lovelace", "Alexandria", "VA", "US"}, {"Priya", "Raman", "San Jose", "CA", "IN"},
                {"Lukas", "Becker", "Boulder", "CO", "DE"}};
        return List.of(
                new Demo("Solid-state battery electrolyte with ceramic interlayer", ApplicationType.UTILITY, EntityStatus.SMALL,
                        ApplicationStatus.DRAFT, 0, false, "Electrochemistry",
                        "A solid-state lithium battery in which a sulfide electrolyte is separated from the lithium-metal anode by a "
                                + "sintered garnet ceramic interlayer 2-8 micrometres thick, suppressing dendrite growth at high current density.",
                        duo),
                new Demo("Ornamental design for a wearable glucose monitor", ApplicationType.DESIGN, EntityStatus.MICRO,
                        ApplicationStatus.DRAFT, 0, false, "Medical devices", "The ornamental design for a wearable glucose monitor, as shown.", ada),
                new Demo("Low-latency consensus protocol for edge device clusters", ApplicationType.UTILITY, EntityStatus.SMALL,
                        ApplicationStatus.SUBMITTED, 3, false, "Distributed computing",
                        "A leader-rotating Byzantine fault tolerant consensus protocol that pipelines proposals across edge nodes and "
                                + "commits within two network round trips under partial synchrony.",
                        trio),
                new Demo("Biodegradable packaging film from mycelium composites", ApplicationType.UTILITY, EntityStatus.MICRO,
                        ApplicationStatus.INCOMPLETE, 21, false, "Materials science",
                        "A compostable packaging film formed by growing fungal mycelium through a lignocellulosic mat and hot-pressing "
                                + "the composite with a chitosan binder.",
                        ada),
                new Demo("Method for passive radiative cooling of building rooftops", ApplicationType.PROVISIONAL, EntityStatus.SMALL,
                        ApplicationStatus.PRE_EXAM, 12, false, "Building technology",
                        "A multilayer polymer-silica metamaterial coating that reflects solar radiation and emits in the 8-13 micrometre "
                                + "atmospheric window, cooling rooftops below ambient temperature without power.",
                        duo),
                new Demo("Neural network accelerator with sparse attention cache", ApplicationType.UTILITY, EntityStatus.LARGE,
                        ApplicationStatus.DOCKETED, 45, false, "Computer architecture",
                        "A hardware accelerator that stores key/value tensors of transformer attention in a compressed sparse cache and "
                                + "skips multiply-accumulate operations for pruned attention heads.",
                        trio),
                new Demo("Autonomous drone battery swap station", ApplicationType.UTILITY, EntityStatus.SMALL,
                        ApplicationStatus.UNDER_EXAMINATION, 190, true, "Robotics",
                        "A landing platform with a self-centring cradle and a robotic arm that exchanges a depleted drone battery for a "
                                + "charged one in under thirty seconds.",
                        duo),
                new Demo("Privacy-preserving federated learning for medical imaging", ApplicationType.UTILITY, EntityStatus.LARGE,
                        ApplicationStatus.NON_FINAL_REJECTION, 420, true, "Machine learning",
                        "A federated training scheme in which hospitals share differentially private, secure-aggregated gradient updates "
                                + "of a segmentation model without exchanging patient images.",
                        trio),
                new Demo("Hydrogen fuel cell membrane with graphene oxide coating", ApplicationType.UTILITY, EntityStatus.SMALL,
                        ApplicationStatus.RESPONSE_FILED, 510, true, "Energy",
                        "A proton exchange membrane coated with a few-layer graphene oxide film that reduces hydrogen crossover while "
                                + "retaining proton conductivity at low humidity.",
                        ada),
                new Demo("Smart irrigation controller using soil moisture forecasting", ApplicationType.UTILITY, EntityStatus.MICRO,
                        ApplicationStatus.FINAL_REJECTION, 640, true, "Agriculture",
                        "An irrigation controller that forecasts root-zone soil moisture from weather data and sensor history and "
                                + "schedules watering to keep moisture within a crop-specific band.",
                        duo),
                new Demo("Quantum-resistant key exchange for IoT devices", ApplicationType.UTILITY, EntityStatus.SMALL,
                        ApplicationStatus.ALLOWED, 700, true, "Cryptography",
                        "A lattice-based key encapsulation handshake tailored to microcontrollers with under 64 KiB of RAM, using "
                                + "streaming polynomial multiplication.",
                        trio),
                new Demo("Self-healing concrete with encapsulated bacteria", ApplicationType.UTILITY, EntityStatus.LARGE,
                        ApplicationStatus.ISSUED, 900, true, "Civil engineering",
                        "Concrete containing calcium-lactate capsules and dormant Bacillus spores that precipitate calcite to seal cracks "
                                + "when water enters.",
                        duo),
                new Demo("Folding bicycle frame with magnetic latch", ApplicationType.UTILITY, EntityStatus.MICRO,
                        ApplicationStatus.ABANDONED, 480, false, "Transportation",
                        "A folding bicycle frame whose hinge is held closed by a neodymium magnetic latch with a mechanical safety pin.",
                        ada));
    }
}
