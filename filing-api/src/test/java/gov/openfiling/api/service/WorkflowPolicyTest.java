package gov.openfiling.api.service;

import static gov.openfiling.api.model.ApplicationStatus.ALLOWED;
import static gov.openfiling.api.model.ApplicationStatus.DOCKETED;
import static gov.openfiling.api.model.ApplicationStatus.DRAFT;
import static gov.openfiling.api.model.ApplicationStatus.FINAL_REJECTION;
import static gov.openfiling.api.model.ApplicationStatus.INCOMPLETE;
import static gov.openfiling.api.model.ApplicationStatus.ISSUED;
import static gov.openfiling.api.model.ApplicationStatus.NON_FINAL_REJECTION;
import static gov.openfiling.api.model.ApplicationStatus.PRE_EXAM;
import static gov.openfiling.api.model.ApplicationStatus.RESPONSE_FILED;
import static gov.openfiling.api.model.ApplicationStatus.SUBMITTED;
import static gov.openfiling.api.model.ApplicationStatus.UNDER_EXAMINATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.openfiling.api.dto.ActionOption;
import gov.openfiling.api.dto.ApplicationPermissions;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.Document;
import gov.openfiling.api.model.DocumentCode;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.User;
import gov.openfiling.api.model.WorkflowAction;
import gov.openfiling.api.security.Viewer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every workflow action &times; every status &times; every viewer capacity, checked against an
 * independent transcription of the contract's action table.
 */
class WorkflowPolicyTest {

    enum Kind { OWNER, EXAMINER, ADMIN, STRANGER }

    /** Who (if anyone) the application is assigned to, relative to the viewer. */
    enum Assignment { NONE, SELF, OTHER }

    private final WorkflowPolicy policy = new WorkflowPolicy();

    private final User owner = TestData.inventor("Owner");
    private final User otherExaminer = TestData.examiner("Other");

    record Scenario(Kind kind, Assignment assignment, ApplicationStatus status, WorkflowAction action) {
    }

    static Stream<Arguments> everyCombination() {
        List<Arguments> args = new ArrayList<>();
        for (WorkflowAction action : WorkflowAction.values()) {
            for (ApplicationStatus status : ApplicationStatus.values()) {
                for (Kind kind : Kind.values()) {
                    for (Assignment assignment : Assignment.values()) {
                        if (assignment == Assignment.SELF && (kind == Kind.OWNER || kind == Kind.STRANGER)) {
                            continue;
                        }
                        args.add(Arguments.of(new Scenario(kind, assignment, status, action)));
                    }
                }
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCombination")
    void isAllowed_everyActionStatusAndRole_matchesContractTable(Scenario s) {
        User viewerUser = switch (s.kind()) {
            case OWNER -> owner;
            case EXAMINER -> TestData.examiner("Viewer");
            case ADMIN -> TestData.admin("Admin");
            case STRANGER -> TestData.inventor("Stranger");
        };
        PatentApplication app = TestData.application(owner, s.status());
        switch (s.assignment()) {
            case SELF -> app.setExaminer(viewerUser);
            case OTHER -> app.setExaminer(otherExaminer);
            case NONE -> app.setExaminer(null);
        }
        Viewer viewer = TestData.viewer(viewerUser);

        boolean expected = expected(s);

        assertThat(policy.isAllowed(s.action(), app, viewer)).as(s.toString()).isEqualTo(expected);
        assertThat(policy.allowedActions(app, viewer).contains(s.action())).isEqualTo(expected);
        assertThat(policy.denialReason(s.action(), app, viewer).isEmpty()).isEqualTo(expected);
    }

    /** The contract's workflow table, transcribed independently of the implementation. */
    private static boolean expected(Scenario s) {
        ApplicationStatus st = s.status();
        boolean active = st != DRAFT && !st.isTerminal();
        boolean owner = s.kind() == Kind.OWNER;
        boolean examiner = s.kind() == Kind.EXAMINER || s.kind() == Kind.ADMIN;
        boolean assigned = (s.kind() == Kind.EXAMINER && s.assignment() == Assignment.SELF) || s.kind() == Kind.ADMIN;
        return switch (s.action()) {
            case CLAIM -> examiner && active && s.assignment() == Assignment.NONE;
            case RELEASE -> active && s.assignment() != Assignment.NONE && assigned;
            case START_PRE_EXAM -> examiner && st == SUBMITTED;
            case NOTICE_MISSING_PARTS -> examiner && (st == SUBMITTED || st == PRE_EXAM);
            case CURE_MISSING_PARTS -> owner && st == INCOMPLETE;
            case DOCKET -> examiner && st == PRE_EXAM;
            case START_EXAMINATION -> assigned && (st == DOCKETED || st == RESPONSE_FILED);
            case ISSUE_NON_FINAL_REJECTION, ISSUE_FINAL_REJECTION, ALLOW -> assigned && st == UNDER_EXAMINATION;
            case FILE_RESPONSE -> owner && (st == NON_FINAL_REJECTION || st == FINAL_REJECTION);
            case ISSUE_PATENT -> examiner && st == ALLOWED;
            case PUBLISH -> examiner && EnumSet.of(PRE_EXAM, DOCKETED, UNDER_EXAMINATION, NON_FINAL_REJECTION, RESPONSE_FILED,
                    FINAL_REJECTION, ALLOWED).contains(st);
            case ABANDON -> active && (owner || (examiner && EnumSet.of(INCOMPLETE, NON_FINAL_REJECTION, FINAL_REJECTION).contains(st)));
        };
    }

    @Test
    void publish_alreadyPublished_isDenied() {
        PatentApplication app = TestData.application(owner, UNDER_EXAMINATION);
        app.setPublished(true);
        Viewer examiner = TestData.viewer(TestData.examiner("E"));

        assertThat(policy.denialReason(WorkflowAction.PUBLISH, app, examiner)).contains("The application is already published");
    }

    @Test
    void examinerActions_onOwnApplication_areDenied() {
        User examinerOwner = TestData.examiner("SelfFiler");
        PatentApplication app = TestData.application(examinerOwner, SUBMITTED);
        Viewer viewer = TestData.viewer(examinerOwner);

        assertThat(policy.isAllowed(WorkflowAction.START_PRE_EXAM, app, viewer)).isFalse();
        assertThat(policy.isAllowed(WorkflowAction.CLAIM, app, viewer)).isFalse();
        assertThat(policy.isAllowed(WorkflowAction.ABANDON, app, viewer)).isTrue(); // as the applicant
        assertThat(policy.canView(app, viewer)).isTrue();
    }

    @Test
    void check_notAllowed_throwsForbiddenWithReason() {
        PatentApplication app = TestData.application(owner, DOCKETED);
        Viewer stranger = TestData.viewer(TestData.inventor("S"));

        assertThatThrownBy(() -> policy.check(WorkflowAction.START_EXAMINATION, app, stranger))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(WorkflowPolicy.EXAMINER_ONLY);
    }

    @Test
    void check_allowed_doesNotThrow() {
        PatentApplication app = TestData.application(owner, SUBMITTED);
        policy.check(WorkflowAction.START_PRE_EXAM, app, TestData.viewer(TestData.examiner("E")));
    }

    @Test
    void denialReason_unassignedExaminerOnAssignedStep_explainsAssignment() {
        PatentApplication app = TestData.assigned(TestData.application(owner, DOCKETED), otherExaminer);

        assertThat(policy.denialReason(WorkflowAction.START_EXAMINATION, app, TestData.viewer(TestData.examiner("E"))))
                .contains(WorkflowPolicy.ASSIGNED_ONLY);
        assertThat(policy.denialReason(WorkflowAction.CLAIM, app, TestData.viewer(TestData.examiner("E"))))
                .contains("The application is already assigned to an examiner");
    }

    @Test
    void denialReason_wrongState_namesStatus() {
        PatentApplication app = TestData.application(owner, PRE_EXAM);

        assertThat(policy.denialReason(WorkflowAction.FILE_RESPONSE, app, TestData.viewer(owner)).orElseThrow())
                .contains("PRE_EXAM");
        assertThat(policy.denialReason(WorkflowAction.FILE_RESPONSE, app, TestData.viewer(TestData.examiner("E"))))
                .contains(WorkflowPolicy.OWNER_ONLY);
        assertThat(policy.denialReason(WorkflowAction.RELEASE, app, TestData.viewer(TestData.examiner("E"))))
                .contains("The application is not assigned to an examiner");
    }

    @Test
    void availableActions_examinerOnSubmitted_listsActionMetadata() {
        PatentApplication app = TestData.application(owner, SUBMITTED);

        List<ActionOption> options = policy.availableActions(app, TestData.viewer(TestData.examiner("E")));

        assertThat(options).extracting(ActionOption::action)
                .containsExactly(WorkflowAction.CLAIM, WorkflowAction.START_PRE_EXAM, WorkflowAction.NOTICE_MISSING_PARTS);
        ActionOption notice = options.get(2);
        assertThat(notice.requiresNote()).isTrue();
        assertThat(notice.toStatus()).isEqualTo(INCOMPLETE);
        assertThat(notice.acceptsDocuments()).isTrue();
        assertThat(options.getFirst().toStatus()).isNull();
    }

    @Test
    void availableActions_ownerOnNonFinalRejection_offersResponseAndAbandon() {
        PatentApplication app = TestData.application(owner, NON_FINAL_REJECTION);

        assertThat(policy.availableActions(app, TestData.viewer(owner))).extracting(ActionOption::action)
                .containsExactly(WorkflowAction.FILE_RESPONSE, WorkflowAction.ABANDON);
        ActionOption abandon = policy.availableActions(app, TestData.viewer(owner)).get(1);
        assertThat(abandon.destructive()).isTrue();
    }

    // ---- visibility ---------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void canView_ownerSeesEveryStatus_strangerNone_examinerAllButDrafts(ApplicationStatus status) {
        PatentApplication app = TestData.application(owner, status);

        assertThat(policy.canView(app, TestData.viewer(owner))).isTrue();
        assertThat(policy.canView(app, TestData.viewer(TestData.inventor("Stranger")))).isFalse();
        assertThat(policy.canView(app, TestData.viewer(TestData.examiner("E")))).isEqualTo(status != DRAFT);
        assertThat(policy.canView(app, TestData.viewer(TestData.admin("A")))).isEqualTo(status != DRAFT);
    }

    // ---- permissions --------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void permissions_owner_followContract(ApplicationStatus status) {
        PatentApplication app = TestData.application(owner, status);

        ApplicationPermissions p = policy.permissions(app, TestData.viewer(owner));

        assertThat(p.canEdit()).isEqualTo(status == DRAFT || status == INCOMPLETE);
        assertThat(p.canDelete()).isEqualTo(status == DRAFT);
        assertThat(p.canSubmit()).isEqualTo(status == DRAFT);
        Set<DocumentCode> expected = switch (status) {
            case DRAFT, INCOMPLETE -> DocumentCode.APPLICANT_CODES;
            case NON_FINAL_REJECTION, FINAL_REJECTION -> EnumSet.of(DocumentCode.RESP, DocumentCode.CLM, DocumentCode.SPEC,
                    DocumentCode.DRW, DocumentCode.IDS, DocumentCode.MISC);
            case ISSUED, ABANDONED -> EnumSet.noneOf(DocumentCode.class);
            default -> EnumSet.of(DocumentCode.IDS, DocumentCode.MISC);
        };
        assertThat(p.uploadableCodes()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(p.canUpload()).isEqualTo(!expected.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void uploadableCodes_examiner_onlyWhenAssignedOrAdminOnActiveApplications(ApplicationStatus status) {
        User examiner = TestData.examiner("E");
        PatentApplication assignedApp = TestData.assigned(TestData.application(owner, status), examiner);
        PatentApplication unassignedApp = TestData.application(owner, status);
        boolean active = status.isActive();

        assertThat(policy.uploadableCodes(assignedApp, TestData.viewer(examiner)))
                .isEqualTo(active ? DocumentCode.EXAMINER_CODES : EnumSet.noneOf(DocumentCode.class));
        assertThat(policy.uploadableCodes(unassignedApp, TestData.viewer(examiner))).isEmpty();
        assertThat(policy.uploadableCodes(unassignedApp, TestData.viewer(TestData.admin("A"))))
                .isEqualTo(active ? DocumentCode.EXAMINER_CODES : EnumSet.noneOf(DocumentCode.class));
        ApplicationPermissions p = policy.permissions(assignedApp, TestData.viewer(examiner));
        assertThat(p.canEdit()).isFalse();
        assertThat(p.canDelete()).isFalse();
        assertThat(p.canSubmit()).isFalse();
    }

    // ---- action required ----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void isActionRequired_ownerOnActionRequiredCategory_examinerWhenAwaitingThem(ApplicationStatus status) {
        User examiner = TestData.examiner("E");
        PatentApplication app = TestData.assigned(TestData.application(owner, status), examiner);

        assertThat(policy.isActionRequired(app, TestData.viewer(owner)))
                .isEqualTo(status == INCOMPLETE || status == NON_FINAL_REJECTION || status == FINAL_REJECTION);
        assertThat(policy.isActionRequired(app, TestData.viewer(examiner)))
                .isEqualTo(ApplicationStatus.AWAITING_EXAMINER.contains(status));
        assertThat(policy.isActionRequired(app, TestData.viewer(TestData.examiner("Other")))).isFalse();
    }

    // ---- document deletion --------------------------------------------------------------------

    @Test
    void canDeleteDocument_ownerDraft_anyStatus() {
        PatentApplication app = TestData.application(owner, DRAFT);
        Document uploaded = TestData.document(app, DocumentCode.SPEC, DocumentStatus.UPLOADED, owner);

        assertThat(policy.canDeleteDocument(uploaded, app, TestData.viewer(owner))).isTrue();
    }

    @Test
    void canDeleteDocument_ownerFiled_onlyWhenNotUploaded() {
        PatentApplication app = TestData.application(owner, PRE_EXAM);
        Viewer viewer = TestData.viewer(owner);

        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.IDS, DocumentStatus.UPLOADED, owner), app, viewer)).isFalse();
        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.IDS, DocumentStatus.PENDING_UPLOAD, owner), app, viewer)).isTrue();
        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.IDS, DocumentStatus.REJECTED, owner), app, viewer)).isTrue();
        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.IDS, DocumentStatus.EXPIRED, owner), app, viewer)).isTrue();
    }

    @Test
    void canDeleteDocument_examiner_onlyOwnUnfinishedUploads() {
        User examiner = TestData.examiner("E");
        PatentApplication app = TestData.assigned(TestData.application(owner, UNDER_EXAMINATION), examiner);
        Viewer viewer = TestData.viewer(examiner);

        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.CTNF, DocumentStatus.PENDING_UPLOAD, examiner), app, viewer)).isTrue();
        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.CTNF, DocumentStatus.UPLOADED, examiner), app, viewer)).isFalse();
        assertThat(policy.canDeleteDocument(TestData.document(app, DocumentCode.SPEC, DocumentStatus.PENDING_UPLOAD, owner), app, viewer)).isFalse();
    }

    @Test
    void issuedApplication_hasNoActionsForAnyone() {
        PatentApplication app = TestData.assigned(TestData.application(owner, ISSUED), otherExaminer);

        assertThat(policy.allowedActions(app, TestData.viewer(owner))).isEmpty();
        assertThat(policy.allowedActions(app, TestData.viewer(otherExaminer))).isEmpty();
        assertThat(policy.allowedActions(app, TestData.viewer(TestData.admin("A")))).isEmpty();
    }
}
