package gov.openfiling.api.service;

import gov.openfiling.api.model.ApplicationInventor;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Builders for service unit tests. */
final class TestData {

    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private TestData() {
    }

    static User user(String name, Role... roles) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(name.toLowerCase() + "@example.test");
        u.setDisplayName(name);
        u.setPasswordHash("{bcrypt}hash");
        u.setRoles(roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.of(roles[0], roles));
        u.setCreatedAt(NOW.minus(Duration.ofDays(30)));
        u.setVersion(0L);
        return u;
    }

    static User inventor(String name) {
        return user(name, Role.INVENTOR);
    }

    static User examiner(String name) {
        User u = user(name, Role.EXAMINER);
        u.setArtUnit("2100");
        return u;
    }

    static User admin(String name) {
        return user(name, Role.ADMIN, Role.EXAMINER);
    }

    static Viewer viewer(User u) {
        return new Viewer(u.getId(), u.getEmail(), u.getRoles());
    }

    static PatentApplication application(User owner, ApplicationStatus status) {
        PatentApplication a = new PatentApplication();
        a.setId(UUID.randomUUID());
        a.setTitle("Solid-state battery electrolyte with ceramic interlayer");
        a.setApplicationType(ApplicationType.UTILITY);
        a.setEntityStatus(EntityStatus.SMALL);
        a.setStatus(status);
        a.setOwner(owner);
        a.setVersion(3L);
        a.setCreatedAt(NOW.minus(Duration.ofDays(40)));
        a.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        if (status.isFiled()) {
            a.setApplicationNumber("19000123");
            a.setConfirmationNumber("4821");
            a.setFilingDate(TODAY.minusDays(12));
            a.setSubmittedAt(NOW.minus(Duration.ofDays(12)));
            a.setSignature("/Ada Lovelace/");
        }
        ApplicationInventor i = new ApplicationInventor();
        i.setGivenName("Ada");
        i.setFamilyName("Lovelace");
        i.setCountry("US");
        a.replaceInventors(List.of(i));
        return a;
    }

    static PatentApplication assigned(PatentApplication app, User examiner) {
        app.setExaminer(examiner);
        app.setArtUnit(examiner.getArtUnit());
        return app;
    }

    static Document document(PatentApplication app, DocumentCode code, DocumentStatus status, User uploader) {
        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setApplication(app);
        d.setDocCode(code);
        d.setVisibility(code.getDefaultVisibility());
        d.setFileName(code.name().toLowerCase() + ".pdf");
        d.setContentType("application/pdf");
        d.setSizeBytes(1234);
        d.setStatus(status);
        d.setSource(code.getSource());
        d.setStorageBucket("openfiling-documents");
        d.setStorageKey((code.getDefaultVisibility().name().toLowerCase()) + "/" + app.getId() + "/" + d.getId() + "/file.pdf");
        d.setUploadedBy(uploader);
        d.setUploadExpiresAt(NOW.plus(Duration.ofMinutes(15)));
        d.setCreatedAt(NOW.minus(Duration.ofMinutes(5)));
        if (status == DocumentStatus.UPLOADED) {
            d.setUploadedAt(NOW.minus(Duration.ofMinutes(1)));
        }
        return d;
    }
}
