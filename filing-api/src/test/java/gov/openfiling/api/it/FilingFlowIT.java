package gov.openfiling.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.model.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** register → create → presign → complete → submit → queue → claim → actions → history → redaction → public. */
class FilingFlowIT extends IntegrationTestBase {

    private JsonNode createApplication(String bearer, String title) throws Exception {
        Map<String, Object> request = Map.of(
                "title", title,
                "applicationType", "UTILITY",
                "entityStatus", "SMALL",
                "abstractText", "A sintered garnet interlayer suppresses dendrites. " + title,
                "technologyField", "Electrochemistry",
                "inventors", List.of(Map.of("givenName", "Ada", "familyName", "Lovelace", "city", "Alexandria", "state", "VA",
                        "country", "US", "citizenship", "US")));
        return body(mvc.perform(postJson("/api/v1/applications", request).header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn());
    }

    private String upload(String bearer, String appId, String code, long size) throws Exception {
        JsonNode init = body(mvc.perform(postJson("/api/v1/applications/" + appId + "/documents", Map.of(
                        "docCode", code, "fileName", code.toLowerCase() + " file.pdf", "contentType", "application/pdf", "sizeBytes", size))
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.headers['Content-Type']").value("application/pdf"))
                .andExpect(jsonPath("$.document.status").value("PENDING_UPLOAD"))
                .andReturn());
        return init.get("document").get("id").asString();
    }

    private void complete(String bearer, String docId) throws Exception {
        mvc.perform(post("/api/v1/documents/" + docId + "/complete").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    private long version(String bearer, String appId) throws Exception {
        return body(mvc.perform(get("/api/v1/applications/" + appId).header("Authorization", bearer)).andExpect(status().isOk())
                .andReturn()).get("version").asLong();
    }

    private JsonNode act(String bearer, String appId, String action, String note, int expectedStatus) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("version", version(bearer, appId));
        if (note != null) {
            request.put("note", note);
        }
        return body(mvc.perform(postJson("/api/v1/applications/" + appId + "/actions", request).header("Authorization", bearer))
                .andExpect(status().is(expectedStatus))
                .andReturn());
    }

    @Test
    void fullFiling_examination_redaction_andPublicAccess() throws Exception {
        String inventor = bearer(register("Ada Inventor"));
        String title = unique("Solid-state battery electrolyte with ceramic interlayer");
        String appId = createApplication(inventor, title).get("id").asString();

        // Submitting without documents: 422 listing each required code.
        mvc.perform(postJson("/api/v1/applications/" + appId + "/submit",
                        Map.of("version", 0, "certify", true, "signature", "/Ada Lovelace/")).header("Authorization", inventor))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[1].message").value("Claims (CLM) is required"));

        String specId = upload(inventor, appId, "SPEC", FILE_SIZE);
        complete(inventor, specId);
        complete(inventor, specId); // idempotent
        complete(inventor, upload(inventor, appId, "CLM", FILE_SIZE));
        complete(inventor, upload(inventor, appId, "ABST", FILE_SIZE));
        String oathId = upload(inventor, appId, "OATH", FILE_SIZE);
        complete(inventor, oathId);
        verify(storage, org.mockito.Mockito.atLeastOnce()).presignUpload(eq("openfiling-documents"), anyString(), eq("application/pdf"), org.mockito.ArgumentMatchers.any());

        // A mismatching size is rejected (422) and the object deleted.
        String bad = upload(inventor, appId, "MISC", 999);
        mvc.perform(post("/api/v1/documents/" + bad + "/complete").header("Authorization", inventor))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        mvc.perform(get("/api/v1/documents/" + bad).header("Authorization", inventor))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(delete("/api/v1/documents/" + bad).header("Authorization", inventor)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/applications/" + appId + "/documents").header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.docCode=='OATH')].redaction").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$[?(@.docCode=='SPEC')].redaction.status").value(org.hamcrest.Matchers.contains("PENDING")));

        // Submit.
        JsonNode filed = body(mvc.perform(postJson("/api/v1/applications/" + appId + "/submit",
                        Map.of("version", version(inventor, appId), "certify", true, "signature", "/Ada Lovelace/"))
                        .header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn());
        String number = filed.get("applicationNumber").asString();
        assertThat(number).matches("19\\d{6}");
        assertThat(filed.get("applicationNumberDisplay").asString()).matches("19/\\d{3},\\d{3}");
        mvc.perform(get("/api/v1/applications/" + appId + "/receipt").header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(4));

        // Examiner queue + workflow.
        String examiner = bearer(staff("Grace Examiner", Role.EXAMINER));
        mvc.perform(get("/api/v1/examiner/queue").param("q", title).header("Authorization", examiner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(appId));
        mvc.perform(get("/api/v1/examiner/queue").param("q", number.substring(2)).header("Authorization", examiner))
                .andExpect(jsonPath("$.content[0].id").value(appId));
        mvc.perform(get("/api/v1/examiner/queue").header("Authorization", inventor)).andExpect(status().isForbidden());

        act(examiner, appId, "CLAIM", null, 200);
        act(examiner, appId, "START_PRE_EXAM", null, 200);
        act(inventor, appId, "DOCKET", null, 403);
        JsonNode docketed = act(examiner, appId, "DOCKET", null, 200);
        assertThat(docketed.get("artUnit").asString()).isEqualTo("2100");
        act(examiner, appId, "PUBLISH", null, 200);
        act(examiner, appId, "START_EXAMINATION", null, 200);
        act(examiner, appId, "ISSUE_NON_FINAL_REJECTION", null, 422);
        JsonNode rejected = act(examiner, appId, "ISSUE_NON_FINAL_REJECTION", "Claims 1-3 anticipated by Smith.", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("NON_FINAL_REJECTION");

        // Inventor sees the action and must upload a RESP first.
        JsonNode inventorView = body(mvc.perform(get("/api/v1/applications/" + appId).header("Authorization", inventor)).andReturn());
        assertThat(inventorView.get("actionRequired").asBoolean()).isTrue();
        List<String> actions = new ArrayList<>();
        inventorView.get("availableActions").forEach(a -> actions.add(a.get("action").asString()));
        assertThat(actions).containsExactly("FILE_RESPONSE", "ABANDON");
        act(inventor, appId, "FILE_RESPONSE", "Amended claim 1.", 422);
        complete(inventor, upload(inventor, appId, "RESP", FILE_SIZE));
        act(inventor, appId, "FILE_RESPONSE", "Amended claim 1.", 200);

        // Stale version → 409.
        mvc.perform(postJson("/api/v1/applications/" + appId + "/actions", Map.of("action", "START_EXAMINATION", "version", 0))
                        .header("Authorization", examiner))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/applications/" + appId + "/history").header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventCode").value("CREATED"))
                .andExpect(jsonPath("$[?(@.eventCode=='SUBMIT')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.eventCode=='ISSUE_NON_FINAL_REJECTION')].note")
                        .value(org.hamcrest.Matchers.contains("Claims 1-3 anticipated by Smith.")))
                .andExpect(jsonPath("$[-1].eventCode").value("FILE_RESPONSE"))
                .andExpect(jsonPath("$[-1].actor.role").value("INVENTOR"));

        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
        mvc.perform(get("/api/v1/dashboard").header("Authorization", examiner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examiner.myDocket").value(1))
                .andExpect(jsonPath("$.examiner.awaitingMyAction").value(1));

        // Redaction callbacks from the Lambda: PROCESSING then COMPLETED.
        mvc.perform(internal("/internal/v1/redactions/events", Map.of("documentId", specId, "status", "PROCESSING")))
                .andExpect(status().isNoContent());
        Map<String, Object> completed = new HashMap<>();
        completed.put("documentId", specId);
        completed.put("status", "COMPLETED");
        completed.put("engine", "CLAUDE+RULES");
        completed.put("model", "claude-opus-5-5");
        completed.put("pageCount", 4);
        completed.put("redactionCount", 3);
        completed.put("categories", List.of(Map.of("category", "SSN", "count", 2), Map.of("category", "PHONE", "count", 1)));
        completed.put("publicCopyBucket", "openfiling-public-copies");
        completed.put("publicCopyKey", "public/" + appId + "/" + specId + "/public-copy.pdf");
        completed.put("reportKey", "public/" + appId + "/" + specId + "/redaction-report.json");
        completed.put("durationMs", 2100);
        mvc.perform(internal("/internal/v1/redactions/events", completed))
                .andExpect(status().isNoContent());
        mvc.perform(internal("/internal/v1/redactions/events", Map.of("documentId", java.util.UUID.randomUUID().toString(),
                        "status", "FAILED")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + specId).header("Authorization", inventor))
                .andExpect(jsonPath("$.redaction.status").value("COMPLETED"))
                .andExpect(jsonPath("$.redaction.redactionCount").value(3))
                .andExpect(jsonPath("$.redaction.categories[0].category").value("SSN"))
                .andExpect(jsonPath("$.redaction.publicCopyAvailable").value(true));
        mvc.perform(get("/api/v1/documents/" + specId + "/download").param("copy", "public").header("Authorization", inventor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("public-copy-spec file.pdf"));

        // Public (anonymous) access after publication.
        mvc.perform(get("/api/v1/public/applications").param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].applicationNumber").value(number))
                .andExpect(jsonPath("$.content[0].publicationNumber").value(org.hamcrest.Matchers.startsWith("US")));
        String display = number.substring(0, 2) + "/" + number.substring(2, 5) + "," + number.substring(5);
        mvc.perform(get("/api/v1/public/applications/" + display))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].id").value(specId))
                .andExpect(jsonPath("$.history[?(@.eventCode=='CREATED')]").isEmpty())
                .andExpect(jsonPath("$.inventors[0].name").value("Ada Lovelace"));
        mvc.perform(get("/api/v1/public/documents/" + specId + "/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("openfiling-public-copies")));
        mvc.perform(get("/api/v1/public/documents/" + oathId + "/download")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/stats")).andExpect(status().isOk());

        // Redaction retry by the examiner re-invokes the function.
        mvc.perform(post("/api/v1/documents/" + specId + "/redaction/retry").header("Authorization", examiner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redaction.status").value("PENDING"));
        verify(redactionInvoker).invoke(eq("openfiling-documents"), anyString(), anyLong(), eq("etag"));
    }

    @Test
    void visibility_otherInventor404_examinerCannotSeeDrafts_storageEventVerifies() throws Exception {
        String owner = bearer(register("Owner"));
        String stranger = bearer(register("Stranger"));
        String examiner = bearer(staff("Examiner", Role.EXAMINER));
        String appId = createApplication(owner, unique("Private draft")).get("id").asString();

        mvc.perform(get("/api/v1/applications/" + appId).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + appId).header("Authorization", examiner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + appId + "/history").header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications").header("Authorization", stranger))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Upload confirmed via the S3 event notification instead of /complete.
        String docId = upload(owner, appId, "SPEC", FILE_SIZE);
        String key = jdbc.queryForObject("SELECT storage_key FROM filing.documents WHERE id = ?::uuid", String.class, docId);
        Map<String, Object> event = Map.of("Records", List.of(Map.of("eventName", "ObjectCreated:Put",
                "s3", Map.of("bucket", Map.of("name", "openfiling-documents"), "object", Map.of("key", key, "size", FILE_SIZE)))));
        mvc.perform(internal("/internal/v1/storage/events", event)).andExpect(status().isNoContent());
        mvc.perform(internal("/internal/v1/storage/events", event)).andExpect(status().isNoContent()); // idempotent
        mvc.perform(postJson("/internal/v1/storage/events", event)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/documents/" + docId).header("Authorization", owner)).andExpect(jsonPath("$.status").value("UPLOADED"));
        mvc.perform(get("/api/v1/documents/" + docId).header("Authorization", stranger)).andExpect(status().isNotFound());

        // Edit with the current version, then with a stale one.
        Map<String, Object> update = new HashMap<>(Map.of("title", "Renamed private draft", "applicationType", "UTILITY",
                "entityStatus", "MICRO", "inventors", List.of(Map.of("givenName", "Grace", "familyName", "Hopper")), "version", 0));
        mvc.perform(put("/api/v1/applications/" + appId).contentType("application/json").content(json.writeValueAsString(update))
                        .header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.inventors[0].givenName").value("Grace"));
        mvc.perform(put("/api/v1/applications/" + appId).contentType("application/json").content(json.writeValueAsString(update))
                        .header("Authorization", owner))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/v1/applications/" + appId).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/applications/" + appId).header("Authorization", owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/applications/" + appId).header("Authorization", owner)).andExpect(status().isNotFound());
    }
}
