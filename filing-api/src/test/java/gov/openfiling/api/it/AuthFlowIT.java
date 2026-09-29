package gov.openfiling.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.model.Role;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AuthFlowIT extends IntegrationTestBase {

    @Test
    void refreshRotation_reuseRevokesTheSession() throws Exception {
        JsonNode registered = register("Rotator");
        String first = registered.get("refreshToken").asString();
        assertThat(registered.get("expiresIn").asInt()).isEqualTo(900);
        assertThat(registered.get("refreshExpiresIn").asInt()).isEqualTo(604800);

        JsonNode rotated = body(mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", first)))
                .andExpect(status().isOk()).andReturn());
        String second = rotated.get("refreshToken").asString();
        assertThat(second).isNotEqualTo(first);

        // Replaying the rotated token is treated as theft: 401 and the whole family is revoked.
        mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", first))).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", second))).andExpect(status().isUnauthorized());
        Integer reuseAudits = jdbc.queryForObject(
                "SELECT count(*) FROM filing.audit_events WHERE action = 'AUTH_REFRESH_REUSE' AND actor_id = ?::uuid",
                Integer.class, registered.get("user").get("id").asString());
        assertThat(reuseAudits).isEqualTo(1);
    }

    @Test
    void lockout_afterFiveFailures_returns429WithRetryAfter() throws Exception {
        String email = register("Locked").get("user").get("email").asString();
        for (int i = 1; i <= 4; i++) {
            mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", "wrong-password-" + i)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        }
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", "wrong-password-5")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isTooManyRequests());
        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM filing.audit_events WHERE action = 'AUTH_LOGIN_FAILED' AND actor_email = ?", Integer.class, email);
        assertThat(failures).isGreaterThanOrEqualTo(5);
    }

    @Test
    void sessions_logout_passwordChange_andAdminUnlock() throws Exception {
        JsonNode registered = register("Sessions");
        String email = registered.get("user").get("email").asString();
        String userId = registered.get("user").get("id").asString();
        JsonNode second = login(email, PASSWORD);
        String bearer = bearer(second);

        mvc.perform(get("/api/v1/account/sessions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", registered.get("refreshToken").asString())))
                .andExpect(status().isNoContent());
        JsonNode sessions = body(mvc.perform(get("/api/v1/account/sessions").header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(1)).andReturn());
        mvc.perform(delete("/api/v1/account/sessions/" + sessions.get(0).get("id").asString()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", second.get("refreshToken").asString())))
                .andExpect(status().isUnauthorized());

        mvc.perform(patch("/api/v1/account/profile").contentType("application/json").content("{\"displayName\":\"Renamed\"}")
                        .header("Authorization", bearer))
                .andExpect(jsonPath("$.displayName").value("Renamed"));
        mvc.perform(postJson("/api/v1/account/password", Map.of("currentPassword", "nope-nope-1", "newPassword", "another-pass-2"))
                        .header("Authorization", bearer))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(postJson("/api/v1/account/password", Map.of("currentPassword", PASSWORD, "newPassword", "another-pass-2"))
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
        login(email, "another-pass-2");

        // Admin: grant EXAMINER, then the user sees the examiner queue after re-login.
        String admin = bearer(staff("Root Admin", Role.ADMIN, Role.EXAMINER));
        mvc.perform(get("/api/v1/admin/users").param("q", email).header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].roles[0]").value("INVENTOR"));
        mvc.perform(patch("/api/v1/admin/users/" + userId).contentType("application/json")
                        .content("{\"roles\":[\"INVENTOR\",\"EXAMINER\"],\"artUnit\":\"3600\",\"unlock\":true}")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.artUnit").value("3600"));
        mvc.perform(get("/api/v1/examiner/stats").header("Authorization", bearer(login(email, "another-pass-2"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit").param("resourceId", userId).param("action", "ADMIN_USER_UPDATED").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("ADMIN_USER_UPDATED"))
                .andExpect(jsonPath("$.content[0].details.artUnit").value("3600"));
        mvc.perform(get("/api/v1/admin/system").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].name").value("database"))
                .andExpect(jsonPath("$.components[0].status").value("UP"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @Test
    void register_duplicateEmail_returns409_andPlatformEndpointsWork() throws Exception {
        String email = register("Dup").get("user").get("email").asString();
        mvc.perform(postJson("/api/v1/auth/register", Map.of("email", email.toUpperCase(), "password", PASSWORD, "displayName", "D")))
                .andExpect(status().isConflict());

        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/applications/{id}/actions']").exists());
        mvc.perform(get("/api/v1/reference")).andExpect(status().isOk()).andExpect(jsonPath("$.statuses.length()").value(12));
        mvc.perform(post("/api/v1/applications")).andExpect(status().isUnauthorized());
    }
}
