package com.thang.chargeops.notification;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.notification.controller.NotificationController;
import com.thang.chargeops.notification.service.NotificationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import({GlobalHandlerError.class, NotificationControllerTest.Security.class})
class NotificationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationInboxService inbox;

    @TestConfiguration @EnableMethodSecurity static class Security {
        @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }

    @Test @WithMockUser(roles="DRIVER") void scopedListKeepsPageMetadata() throws Exception {
        UUID station = UUID.randomUUID();
        when(inbox.list(1, 5, true, "driver", "booking", station))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
        mvc.perform(get("/api/v1/notifications").param("size", "5").param("unread", "true")
                        .param("context", "driver").param("category", "booking").param("stationId", station.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.page").value(1)).andExpect(jsonPath("$.meta.size").value(5));
        verify(inbox).list(1, 5, true, "driver", "booking", station);
    }

    @Test @WithMockUser void countIsAnEnvelopeAndForwardsFilters() throws Exception {
        when(inbox.unreadCount("owner", "finance", null)).thenReturn(3L);
        mvc.perform(get("/api/v1/notifications/unread-count").param("context", "owner").param("category", "finance"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(3));
    }

    @Test @WithMockUser void mutationResponsesAre200AndPreserveContext() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(patch("/api/v1/notifications/{id}/read", id).param("context", "driver"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(patch("/api/v1/notifications/read-all").param("context", "owner").param("category", "finance"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(patch("/api/v1/notifications/{id}/dismiss", id).param("context", "driver"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dismissed").value(true));
        verify(inbox).markRead(id, "driver", null, null);
        verify(inbox).markAllRead("owner", "finance", null);
        verify(inbox).dismiss(id, "driver", null, null);
    }

    @Test @WithMockUser void outsideScopeAndWrongRoleKeep404And403() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new AppException(CommonErrorCode.RESOURCE_NOT_FOUND)).when(inbox).markRead(id, null, null, null);
        mvc.perform(patch("/api/v1/notifications/{id}/read", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SYS_404"));
        when(inbox.unreadCount("admin", null, null)).thenThrow(new AppException(AuthErrorCode.ACCESS_DENIED));
        mvc.perform(get("/api/v1/notifications/unread-count").param("context", "admin"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_004"));
    }

    @Test @WithMockUser void rejectsOversizedPagesBeforeService() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("size", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(inbox);
    }
}
