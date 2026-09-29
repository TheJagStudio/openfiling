package gov.openfiling.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.openfiling.api.dto.CountResponse;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.service.AdminService;
import gov.openfiling.api.service.DashboardService;
import gov.openfiling.api.service.ExaminerService;
import gov.openfiling.api.service.NotificationService;

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

@WebMvcTest({ExaminerController.class, AdminController.class, DashboardController.class, NotificationController.class})
@Import(WebSliceConfig.class)
class StaffControllersTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExaminerService examiner;

    @MockitoBean
    AdminService admin;

    @MockitoBean
    DashboardService dashboard;

    @MockitoBean
    NotificationService notifications;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void queue_inventor_returns403() throws Exception {
        mvc.perform(get("/api/v1/examiner/queue").with(Jwts.inventor())).andExpect(status().isForbidden());
        verifyNoInteractions(examiner);
    }

    @Test
    void queue_examinerWithFilters_returns200() throws Exception {
        given(examiner.queue(any(), any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/examiner/queue").with(Jwts.examiner())
                        .param("assigned", "me").param("status", "SUBMITTED,PRE_EXAM").param("filedFrom", "2026-01-01")
                        .param("artUnit", "2100"))
                .andExpect(status().isOk());
        verify(examiner).queue(any(), any(ExaminerService.QueueFilter.class), any());
    }

    @Test
    void queue_badAssignedOrDate_returns400() throws Exception {
        mvc.perform(get("/api/v1/examiner/queue").with(Jwts.examiner()).param("assigned", "everyone")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/examiner/queue").with(Jwts.examiner()).param("filedFrom", "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void stats_admin_returns200() throws Exception {
        mvc.perform(get("/api/v1/examiner/stats").with(Jwts.admin())).andExpect(status().isOk());
    }

    @Test
    void adminRoutes_examiner_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/users").with(Jwts.examiner())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/system").with(Jwts.examiner())).andExpect(status().isForbidden());
        verifyNoInteractions(admin);
    }

    @Test
    void adminUsers_admin_returns200() throws Exception {
        given(admin.users(any(), any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/admin/users").with(Jwts.admin()).param("role", "EXAMINER")).andExpect(status().isOk());
    }

    @Test
    void createUser_invalid_returns400_valid_returns201() throws Exception {
        mvc.perform(post("/api/v1/admin/users").with(Jwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x\",\"displayName\":\"G\",\"password\":\"short\",\"roles\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/users").with(Jwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"g@x.io\",\"displayName\":\"G\",\"password\":\"Examiner#12345\",\"roles\":[\"EXAMINER\"],\"artUnit\":\"2100\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void updateUser_selfDemotion_returns422_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(admin.updateUser(any(), eq(id), any())).willThrow(BusinessRuleException.of("roles", "You cannot remove your own ADMIN role"));
        UUID missing = UUID.randomUUID();
        given(admin.updateUser(any(), eq(missing), any())).willThrow(NotFoundException.of("User", missing));

        mvc.perform(patch("/api/v1/admin/users/{id}", id).with(Jwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"EXAMINER\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("roles"));
        mvc.perform(patch("/api/v1/admin/users/{id}", missing).with(Jwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unlock\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void audit_filtersBound() throws Exception {
        given(admin.audit(any(), any(), any(), any(), any(), any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/admin/audit").with(Jwts.admin()).param("action", "WORKFLOW_ACTION")
                        .param("from", "2026-09-01T00:00:00Z"))
                .andExpect(status().isOk());
        verify(admin).audit(eq(null), eq(AuditAction.WORKFLOW_ACTION), eq(null), eq(null), any(), eq(null), any());
    }

    @Test
    void dashboardAndNotifications_authenticated() throws Exception {
        given(notifications.unreadCount(any())).willReturn(new CountResponse(3));
        given(notifications.list(any(), eq(true), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/dashboard").with(Jwts.inventor())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").with(Jwts.inventor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").with(Jwts.inventor())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()).with(Jwts.inventor())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/read-all").with(Jwts.inventor())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
    }
}
