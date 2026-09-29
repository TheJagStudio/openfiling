package gov.openfiling.api.it;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.model.Role;
import gov.openfiling.api.service.AuthService;
import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.RedactionInvoker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full application against {@code openfiling_test} (schema {@code filing}). Object storage and the
 * redaction function are mocked; every test creates uniquely named users and deletes their rows
 * afterwards, so the suite is re-runnable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    static final String INTERNAL_TOKEN = "dev-internal-token-change-me";
    static final String PASSWORD = "integration-pass-1";
    static final long FILE_SIZE = 1234;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService authService;

    @MockitoBean
    DocumentStorage storage;

    @MockitoBean
    RedactionInvoker redactionInvoker;

    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void stubStorage() {
        given(storage.presignUpload(anyString(), anyString(), anyString(), any())).willAnswer(inv -> new DocumentStorage.PresignedUrl(
                "http://localhost:9000/" + inv.getArgument(0) + "/" + inv.getArgument(1) + "?X-Amz-Signature=test", "PUT",
                Map.of("Content-Type", "application/pdf"), Instant.now().plusSeconds(900)));
        given(storage.presignDownload(anyString(), anyString(), anyString(), anyString(), any())).willAnswer(inv ->
                new DocumentStorage.PresignedUrl("http://localhost:9000/" + inv.getArgument(0) + "/" + inv.getArgument(1) + "?sig",
                        "GET", Map.of(), Instant.now().plusSeconds(300)));
        given(storage.head(anyString(), anyString())).willReturn(
                Optional.of(new DocumentStorage.ObjectHead(FILE_SIZE, "application/pdf", "etag", Instant.now())));
        given(storage.readPrefix(anyString(), anyString(), anyInt())).willReturn("%PDF-".getBytes(StandardCharsets.US_ASCII));
        given(storage.bucketHealth(anyString())).willReturn(new DocumentStorage.BucketHealth(true, "mock bucket"));
        given(redactionInvoker.health()).willReturn(new RedactionInvoker.Health(true, "mock function"));
    }

    @AfterEach
    void cleanUp() {
        for (String email : emails) {
            // applications cascade to inventors, documents, redactions, history and notifications
            jdbc.update("DELETE FROM filing.applications WHERE owner_id IN (SELECT id FROM filing.users WHERE email = ?)", email);
            jdbc.update("DELETE FROM filing.users WHERE email = ?", email);
        }
    }

    static String unique(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append("-zq");
        for (int i = 0; i < 10; i++) {
            sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    /** Registers an inventor over HTTP and returns the auth response. */
    JsonNode register(String name) throws Exception {
        String email = unique(name.replace(' ', '.').toLowerCase()) + "@example.test";
        emails.add(email);
        return body(mvc.perform(postJson("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", name)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    /** Creates a staff user directly (as an admin would) and logs in. */
    JsonNode staff(String name, Role... roles) throws Exception {
        String email = unique(name.replace(' ', '.').toLowerCase()) + "@example.test";
        emails.add(email);
        authService.createUser(email, PASSWORD, name, EnumSet.of(roles[0], roles), "2100");
        return login(email, PASSWORD);
    }

    JsonNode login(String email, String password) throws Exception {
        return body(mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", password)))
                .andExpect(status().isOk())
                .andReturn());
    }

    static String bearer(JsonNode auth) {
        return "Bearer " + auth.get("accessToken").asString();
    }

    MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    MockHttpServletRequestBuilder internal(String url, Object body) {
        return postJson(url, body).header("X-Internal-Token", INTERNAL_TOKEN);
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
