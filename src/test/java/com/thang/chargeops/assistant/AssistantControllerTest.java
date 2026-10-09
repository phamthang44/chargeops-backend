package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.infra.security.*;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssistantController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtRoleConverter.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, GlobalHandlerError.class})
class AssistantControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AssistantService service;
    @MockitoBean private UserProfileRepository profiles;
    @MockitoBean private CurrentActorContext actors;
    @MockitoBean private JwtDecoder decoder;

    @Test
    void anonymousCannotSpendProviderCredits() throws Exception {
        mvc.perform(post("/api/v1/assistant/ask").contentType("application/json")
                        .content("{\"question\":\"Question\",\"locale\":\"vi\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedRequestReturnsChargeOpsEnvelopeAndSources() throws Exception {
        when(service.ask(any())).thenReturn(new AssistantAnswer("Hoàn 0%.", "vi", "message",
                List.of(new AssistantAnswer.Citation("doc", "refunds.vi.md", "CR-02", "Rule", 0.98))));
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))).contentType("application/json")
                        .content("{\"question\":\"Question\",\"locale\":\"vi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("Hoàn 0%."))
                .andExpect(jsonPath("$.data.citations[0].documentName").value("refunds.vi.md"))
                .andExpect(jsonPath("$.data.conversationId").doesNotExist())
                .andExpect(jsonPath("$.meta.traceId").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"question\":\" \"}", "{\"question\":\"Q\",\"locale\":\"fr\"}"})
    void invalidInputDoesNotReachService(String body) throws Exception {
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedQuestionIsRejected() throws Exception {
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))).contentType("application/json")
                        .content("{\"question\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void providerFailureUsesSafeErrorEnvelope() throws Exception {
        when(service.ask(any())).thenThrow(new AppException(AssistantErrorCode.UNAVAILABLE));
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))).contentType("application/json")
                        .content("{\"question\":\"Question\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ASSISTANT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "ADMIN", "DRIVER", "USER"})
    void nonOwnerCannotReachAssistantService(String role) throws Exception {
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
                        .contentType("application/json").content("{\"question\":\"Q\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_004"));
        verifyNoInteractions(service);
    }

    @Test
    void dailyLimitReturnsMachineReadableResetWithoutAnswer() throws Exception {
        when(service.ask(any())).thenThrow(AppException.withDetails(AssistantErrorCode.QUOTA_EXCEEDED,
                java.util.Map.of("scope", "OWNER_DAY", "limit", 10, "retryAfterSeconds", 60,
                        "resetAt", "2026-10-08T17:00:00Z")));
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER")))
                        .contentType("application/json").content("{\"question\":\"Q\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("ASSISTANT_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.details.scope").value("OWNER_DAY"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void askWithConversationIdReturnsConversationIdInEnvelope() throws Exception {
        when(service.ask(any())).thenReturn(new AssistantAnswer("Hoàn 0%.", "vi", "message", "conv-123",
                List.of(new AssistantAnswer.Citation("doc", "refunds.vi.md", "CR-02", "Rule", 0.98))));
        mvc.perform(post("/api/v1/assistant/ask").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))).contentType("application/json")
                        .content("{\"question\":\"Question\",\"locale\":\"vi\",\"conversationId\":\"conv-123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("Hoàn 0%."))
                .andExpect(jsonPath("$.data.conversationId").value("conv-123"));
    }

    @Test
    void getConversationsReturnsListForOwner() throws Exception {
        when(service.getConversations(null, 20)).thenReturn(new ConversationPage(
                List.of(new ConversationSummary("conv-1", "Hỏi chính sách", java.time.Instant.now(), java.time.Instant.now())),
                false, null));
        mvc.perform(get("/api/v1/assistant/conversations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("conv-1"))
                .andExpect(jsonPath("$.data.items[0].title").value("Hỏi chính sách"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void getConversationsRejectsNonOwner() throws Exception {
        mvc.perform(get("/api/v1/assistant/conversations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isForbidden());
        verify(service, never()).getConversations(any(), any());
    }

    @Test
    void getMessagesReturnsListForOwner() throws Exception {
        when(service.getMessages("conv-1", null, 20)).thenReturn(new MessagePage(
                List.of(new ChatTurn("msg-1", "conv-1", "Query?", "Answer.", "normal", java.time.Instant.now(), List.of())),
                false, null));
        mvc.perform(get("/api/v1/assistant/conversations/conv-1/messages").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("msg-1"))
                .andExpect(jsonPath("$.data.items[0].query").value("Query?"))
                .andExpect(jsonPath("$.data.items[0].answer").value("Answer."));
    }

    @Test
    void getMessagesRejectsNonOwner() throws Exception {
        mvc.perform(get("/api/v1/assistant/conversations/conv-1/messages").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"))))
                .andExpect(status().isForbidden());
        verify(service, never()).getMessages(any(), any(), any());
    }
}
