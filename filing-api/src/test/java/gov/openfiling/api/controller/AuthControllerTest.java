package gov.openfiling.api.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.dto.AuthResponse;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.exception.AccountLockedException;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.InvalidCredentialsException;
import gov.openfiling.api.exception.InvalidRefreshTokenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.service.AccountService;
import gov.openfiling.api.service.AuthService;

import java.time.Duration;
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

@WebMvcTest({AuthController.class, AccountController.class})
@Import(WebSliceConfig.class)
class AuthControllerTest {

    static final String REGISTER = "{\"email\":\"a@b.co\",\"password\":\"abcdefghi1\",\"displayName\":\"A\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService auth;

    @MockitoBean
    AccountService account;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static AuthResponse authResponse() {
        UserResponse user = new UserResponse(UUID.randomUUID(), "a@b.co", "A", List.of("INVENTOR"), null, Instant.now(), null);
        return AuthResponse.bearer("jwt", 900, "refresh", 604800, user);
    }

    @Test
    void register_valid_returns201WithBothTokens() throws Exception {
        given(auth.register(any())).willReturn(authResponse());

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.refreshExpiresIn").value(604800))
                .andExpect(jsonPath("$.user.roles[0]").value("INVENTOR"));
    }

    @Test
    void register_weakPasswordAndBadEmail_returns400WithAllFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\",\"password\":\"onlyletters\",\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("displayName")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(auth);
    }

    @Test
    void register_duplicate_returns409() throws Exception {
        given(auth.register(any())).willThrow(new ConflictException("An account with this email already exists"));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/register"));
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    @Test
    void login_badCredentials_returns401SameMessage() throws Exception {
        given(auth.login(any())).willThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"whatever1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void login_locked_returns429WithRetryAfter() throws Exception {
        given(auth.login(any())).willThrow(new AccountLockedException(Duration.ofMinutes(15)));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\",\"password\":\"whatever1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void refresh_invalid_returns401_evenWithStaleBearerHeader() throws Exception {
        given(auth.refresh("old")).willThrow(new InvalidRefreshTokenException());

        mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer expired.jwt.value")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"old\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The refresh token is invalid or expired; sign in again"));
    }

    @Test
    void refresh_valid_returnsRotatedTokens() throws Exception {
        given(auth.refresh("current")).willReturn(authResponse());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"current\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt"));
    }

    @Test
    void logout_returns204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"x\"}"))
                .andExpect(status().isNoContent());
        verify(auth).logout("x");
    }

    @Test
    void me_withoutToken_returns401Problem() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")));
    }

    @Test
    void me_withToken_returnsUser() throws Exception {
        UUID id = UUID.randomUUID();
        given(auth.me(id)).willReturn(new UserResponse(id, "a@b.co", "A", List.of("INVENTOR"), null, Instant.now(), null));

        mvc.perform(get("/api/v1/auth/me").with(Jwts.as(id, Role.INVENTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void me_unknownUser_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(auth.me(id)).willThrow(NotFoundException.of("User", id));

        mvc.perform(get("/api/v1/auth/me").with(Jwts.as(id, Role.INVENTOR))).andExpect(status().isNotFound());
    }

    @Test
    void changePassword_wrongCurrent_returns422WithFieldError() throws Exception {
        org.mockito.BDDMockito.willThrow(BusinessRuleException.of("currentPassword", "The current password is incorrect"))
                .given(account).changePassword(any(), any());

        mvc.perform(post("/api/v1/account/password").with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"abcdefghi12\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
    }

    @Test
    void updateProfile_blankName_returns400() throws Exception {
        mvc.perform(patch("/api/v1/account/profile").with(Jwts.inventor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sessions_listAndRevoke_returnJsonAnd204() throws Exception {
        UUID userId = UUID.randomUUID();
        given(account.sessions(any())).willReturn(List.of());

        mvc.perform(get("/api/v1/account/sessions").with(Jwts.as(userId, Role.INVENTOR))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/account/sessions/{id}",
                        UUID.randomUUID()).with(Jwts.as(userId, Role.INVENTOR)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/account/sessions/revoke-all").with(Jwts.as(userId, Role.INVENTOR))).andExpect(status().isNoContent());
        verify(account).revokeAll(any());
        verify(account).revokeSession(any(), any());
    }
}
