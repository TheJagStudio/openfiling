package gov.openfiling.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.PublicStats;
import gov.openfiling.api.dto.ReferenceResponse;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.service.PublicService;
import gov.openfiling.api.service.RedactionService;
import gov.openfiling.api.service.ReferenceService;
import gov.openfiling.api.service.StorageEventService;

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

@WebMvcTest({PublicController.class, ReferenceController.class, InternalController.class})
@Import(WebSliceConfig.class)
class PublicAndInternalControllerTest {

    static final String TOKEN = "dev-internal-token-change-me";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PublicService publicService;

    @MockitoBean
    ReferenceService reference;

    @MockitoBean
    StorageEventService storageEvents;

    @MockitoBean
    RedactionService redactions;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void publicEndpoints_anonymous_ok_evenWithGarbageBearer() throws Exception {
        given(publicService.search(any(), any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        given(publicService.stats()).willReturn(new PublicStats(1, 2, 3, 4, 5));

        mvc.perform(get("/api/v1/public/applications").header("Authorization", "Bearer garbage")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.redactionsApplied").value(5));
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void publicDetail_displayNumberWithSlash_and404() throws Exception {
        given(publicService.detail("19/000,123")).willThrow(NotFoundException.of("Application", "19/000,123"));
        given(publicService.detail("19000999")).willThrow(NotFoundException.of("Application", "19000999"));

        mvc.perform(get("/api/v1/public/applications/19/000,123")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/applications/19000999")).andExpect(status().isNotFound());
        verify(publicService).detail("19/000,123");
    }

    @Test
    void publicDownload_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(publicService.download(id)).willThrow(NotFoundException.of("Document", id));

        mvc.perform(get("/api/v1/public/documents/{id}/download", id)).andExpect(status().isNotFound());
    }

    @Test
    void reference_anonymous_cacheable() throws Exception {
        given(reference.reference()).willReturn(new ReferenceResponse(List.of(), List.of(), List.of(), List.of(),
                new ReferenceResponse.Limits(1, 2, List.of("application/pdf"))));

        mvc.perform(get("/api/v1/reference"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=3600")))
                .andExpect(jsonPath("$.limits.allowedContentTypes[0]").value("application/pdf"));
    }

    @Test
    void internal_withoutToken_returns401_andJwtIsNotAccepted() throws Exception {
        mvc.perform(post("/internal/v1/storage/events").contentType(MediaType.APPLICATION_JSON).content("{\"Records\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/storage/events").with(Jwts.admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(storageEvents);
    }

    @Test
    void storageEvent_withToken_returns204() throws Exception {
        mvc.perform(post("/internal/v1/storage/events").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Records":[{"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"openfiling-documents"},
                                 "object":{"key":"public/a/b/c.pdf","size":10,"eTag":"x"}},"extra":1}]}"""))
                .andExpect(status().isNoContent());
        verify(storageEvents).handle(any());
    }

    @Test
    void redactionCallback_validationAndUnknownDocument() throws Exception {
        mvc.perform(post("/internal/v1/redactions/events").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentId"));
        willThrow(NotFoundException.of("Document", "x")).given(redactions).handleCallback(any());
        mvc.perform(post("/internal/v1/redactions/events").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + UUID.randomUUID() + "\",\"status\":\"PROCESSING\"}"))
                .andExpect(status().isNotFound());
    }
}
