package gov.openfiling.api.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.dto.ApplicationDetail;
import gov.openfiling.api.dto.ApplicationPermissions;
import gov.openfiling.api.dto.DownloadResponse;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.DocumentRejectedException;
import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.exception.ForbiddenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.exception.UpstreamUnavailableException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.EntityStatus;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.service.ApplicationService;
import gov.openfiling.api.service.DocumentService;
import gov.openfiling.api.service.HistoryService;
import gov.openfiling.api.service.WorkflowService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ApplicationController.class, DocumentController.class})
@Import(WebSliceConfig.class)
class ApplicationControllerTest {

    static final String CREATE = """
            {"title":"Solid-state battery","applicationType":"UTILITY","entityStatus":"SMALL",
             "inventors":[{"givenName":"Ada","familyName":"Lovelace","country":"US","citizenship":"US"}]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ApplicationService applications;

    @MockitoBean
    WorkflowService workflow;

    @MockitoBean
    HistoryService history;

    @MockitoBean
    DocumentService documents;

    @MockitoBean
    JwtDecoder jwtDecoder;

    static ApplicationDetail detail(UUID id, ApplicationStatus status) {
        return new ApplicationDetail(id, null, null, "Solid-state battery", ApplicationType.UTILITY, EntityStatus.SMALL, status,
                status.getLabel(), status.getCategory(), null, Instant.now(), Instant.now(), null, null, null, 0, false, null, false,
                null, null, null, null, "Ada Lovelace", List.of(), null, null, null, null, null, null, 0,
                new ApplicationPermissions(true, true, true, true, List.of()), List.of());
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/applications")).andExpect(status().isUnauthorized());
        verifyNoInteractions(applications);
    }

    @Test
    void list_statusCsvAndPaging_passedToService() throws Exception {
        given(applications.list(any(), any(), any(), any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/applications").with(Jwts.inventor())
                        .param("status", "DRAFT,SUBMITTED").param("sort", "title,asc").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(applications).list(any(), eq(null), eq(List.of(ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED)), eq(null), any());
    }

    @Test
    void list_badStatusOrPageSize_returns400() throws Exception {
        mvc.perform(get("/api/v1/applications").with(Jwts.inventor()).param("status", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/applications").with(Jwts.inventor()).param("size", "500")).andExpect(status().isBadRequest());
    }

    @Test
    void create_valid_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        given(applications.create(any(), any())).willReturn(detail(id, ApplicationStatus.DRAFT));

        mvc.perform(post("/api/v1/applications").with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.statusCategory").value("DRAFT"))
                .andExpect(jsonPath("$.permissions.canSubmit").value(true));
    }

    @Test
    void create_invalid_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/applications").with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"abc\",\"inventors\":[{\"givenName\":\"\",\"familyName\":\"L\",\"country\":\"USA\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("applicationType")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("inventors[0].givenName")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("inventors[0].country")));
        verifyNoInteractions(applications);
    }

    @Test
    void get_invisible_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(applications.get(any(), eq(id))).willThrow(NotFoundException.of("Application", id));

        mvc.perform(get("/api/v1/applications/{id}", id).with(Jwts.inventor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void get_badUuid_returns400() throws Exception {
        mvc.perform(get("/api/v1/applications/not-a-uuid").with(Jwts.inventor())).andExpect(status().isBadRequest());
    }

    @Test
    void update_staleVersion_returns409() throws Exception {
        given(applications.update(any(), any(), any())).willThrow(ConflictException.staleVersion());

        mvc.perform(put("/api/v1/applications/{id}", UUID.randomUUID()).with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE.replace("\"entityStatus\":\"SMALL\",", "\"entityStatus\":\"SMALL\",\"version\":1,")))
                .andExpect(status().isConflict());
    }

    @Test
    void update_missingVersion_returns400() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", UUID.randomUUID()).with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("version")));
    }

    @Test
    void delete_returns204() throws Exception {
        mvc.perform(delete("/api/v1/applications/{id}", UUID.randomUUID()).with(Jwts.inventor())).andExpect(status().isNoContent());
    }

    @Test
    void submit_incomplete_returns422WithDocumentErrors() throws Exception {
        given(applications.submit(any(), any(), any())).willThrow(new BusinessRuleException("The application is not complete",
                List.of(new FieldProblem("documents", "Claims (CLM) is required"))));

        mvc.perform(post("/api/v1/applications/{id}/submit", UUID.randomUUID()).with(Jwts.inventor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"certify\":true,\"signature\":\"/Ada Lovelace/\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("documents"))
                .andExpect(jsonPath("$.errors[0].message").value("Claims (CLM) is required"));
    }

    @Test
    void submit_badSignatureOrNoCertification_returns400() throws Exception {
        mvc.perform(post("/api/v1/applications/{id}/submit", UUID.randomUUID()).with(Jwts.inventor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"certify\":false,\"signature\":\"Ada Lovelace\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("certify")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("signature")));
        verifyNoInteractions(applications);
    }

    @Test
    void action_forbiddenForRole_returns403() throws Exception {
        given(workflow.perform(any(), any(), any())).willThrow(new ForbiddenException("Only an examiner can perform this action"));

        mvc.perform(post("/api/v1/applications/{id}/actions", UUID.randomUUID()).with(Jwts.inventor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START_PRE_EXAM\",\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only an examiner can perform this action"));
    }

    @Test
    void action_unknownActionOrBadArtUnit_returns400() throws Exception {
        mvc.perform(post("/api/v1/applications/{id}/actions", UUID.randomUUID()).with(Jwts.examiner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"FLY\",\"version\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/applications/{id}/actions", UUID.randomUUID()).with(Jwts.examiner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DOCKET\",\"artUnit\":\"21\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("artUnit"));
    }

    @Test
    void action_valid_returnsDetail() throws Exception {
        UUID id = UUID.randomUUID();
        given(workflow.perform(any(), eq(id), any())).willReturn(detail(id, ApplicationStatus.PRE_EXAM));

        mvc.perform(post("/api/v1/applications/{id}/actions", id).with(Jwts.examiner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START_PRE_EXAM\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRE_EXAM"));
    }

    @Test
    void historyAndReceiptAndDocuments_delegate() throws Exception {
        UUID id = UUID.randomUUID();
        given(history.history(any(), eq(id))).willReturn(List.of());
        given(applications.receipt(any(), eq(id))).willThrow(new ConflictException("The application has not been filed yet"));
        given(documents.list(any(), eq(id))).willReturn(List.of());

        mvc.perform(get("/api/v1/applications/{id}/history", id).with(Jwts.inventor())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/{id}/receipt", id).with(Jwts.inventor())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/applications/{id}/documents", id).with(Jwts.inventor())).andExpect(status().isOk());
    }

    @Test
    void startUpload_nonPdf_returns400() throws Exception {
        mvc.perform(post("/api/v1/applications/{id}/documents", UUID.randomUUID()).with(Jwts.inventor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docCode\":\"SPEC\",\"fileName\":\"spec.docx\",\"contentType\":\"application/msword\",\"sizeBytes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("fileName")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("contentType")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("sizeBytes")));
        verifyNoInteractions(documents);
    }

    @Test
    void startUpload_codeNotAllowed_returns403() throws Exception {
        given(documents.initiateUpload(any(), any(), any())).willThrow(new ForbiddenException("You cannot upload"));

        mvc.perform(post("/api/v1/applications/{id}/documents", UUID.randomUUID()).with(Jwts.inventor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docCode\":\"SPEC\",\"fileName\":\"spec.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void complete_rejected_returns422() throws Exception {
        given(documents.complete(any(), any())).willThrow(new DocumentRejectedException("The file is not a PDF (missing %PDF- header)"));

        mvc.perform(post("/api/v1/documents/{id}/complete", UUID.randomUUID()).with(Jwts.inventor()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void download_badCopyParam_returns400_validReturnsUrl() throws Exception {
        UUID id = UUID.randomUUID();
        given(documents.download(any(), eq(id), anyString())).willReturn(new DownloadResponse("http://s3/x", Instant.now(), "spec.pdf"));

        mvc.perform(get("/api/v1/documents/{id}/download", id).param("copy", "everything").with(Jwts.inventor()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents/{id}/download", id).param("copy", "public").with(Jwts.inventor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://s3/x"));
    }

    @Test
    void retryRedaction_inventor_returns403_examinerUpstreamDown_returns503() throws Exception {
        mvc.perform(post("/api/v1/documents/{id}/redaction/retry", UUID.randomUUID()).with(Jwts.inventor()))
                .andExpect(status().isForbidden());
        willThrow(new UpstreamUnavailableException("The redaction service is unavailable; try again later", null))
                .given(documents).retryRedaction(any(), any());
        mvc.perform(post("/api/v1/documents/{id}/redaction/retry", UUID.randomUUID()).with(Jwts.as(UUID.randomUUID(), Role.EXAMINER)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deleteAndGetDocument_delegate() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/documents/{id}", id).with(Jwts.inventor())).andExpect(status().isNoContent());
        given(documents.get(any(), eq(id))).willThrow(NotFoundException.of("Document", id));
        mvc.perform(get("/api/v1/documents/{id}", id).with(Jwts.inventor())).andExpect(status().isNotFound());
    }
}
