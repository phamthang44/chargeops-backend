package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AssistantServiceTest {
    private final CurrentActorContext actors = new CurrentActorContext();
    private final DifyClient dify = mock(DifyClient.class);
    private final AssistantProperties properties = new AssistantProperties();
    private final AssistantService service = new AssistantService(actors, dify, new AssistantRateLimiter(properties));

    @AfterEach
    void clearActor() { actors.clear(); }

    @Test
    void missingInternalProfileCannotCallProvider() {
        assertThatThrownBy(() -> service.ask(new AssistantAskRequest("Question", "vi")))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_DENIED));
        verifyNoInteractions(dify);
    }

    @Test
    void identityComesFromCurrentRequestAndVietnameseIsDefault() {
        UUID user = UUID.randomUUID();
        actors.set(user);
        when(dify.isAvailable()).thenReturn(true);
        AssistantAnswer answer = new AssistantAnswer("Answer", "vi", "id", List.of());
        when(dify.ask(user, "Question", "vi", null)).thenReturn(answer);
        assertThat(service.ask(new AssistantAskRequest("  Question  ", null))).isEqualTo(answer);
        verify(dify).ask(user, "Question", "vi", null);
    }

    @Test
    void askWithConversationIdForwardsToDify() {
        UUID user = UUID.randomUUID();
        actors.set(user);
        when(dify.isAvailable()).thenReturn(true);
        AssistantAnswer answer = new AssistantAnswer("Answer", "vi", "id", "conv-1", List.of());
        when(dify.ask(user, "Question", "vi", "conv-1")).thenReturn(answer);
        assertThat(service.ask(new AssistantAskRequest("Question", "vi", "conv-1"))).isEqualTo(answer);
        verify(dify).ask(user, "Question", "vi", "conv-1");
    }

    @Test
    void readingHistoryDoesNotConsumeRateLimiterQuota() {
        properties.setRequestsPerMinute(1);
        UUID user = UUID.randomUUID();
        actors.set(user);
        when(dify.isAvailable()).thenReturn(true);
        when(dify.getConversations(user, null, 20)).thenReturn(new ConversationPage(List.of(), false, null));
        when(dify.getMessages(user, "conv-1", null, 20)).thenReturn(new MessagePage(List.of(), false, null));

        // Reading conversations and messages repeatedly should never trigger RATE_LIMITED
        service.getConversations(null, 20);
        service.getConversations(null, 20);
        service.getMessages("conv-1", null, 20);
        service.getMessages("conv-1", null, 20);

        verify(dify, times(2)).getConversations(user, null, 20);
        verify(dify, times(2)).getMessages(user, "conv-1", null, 20);
    }

    @Test
    void differentActorsAreSeparatedAndLimitPreventsAnotherProviderCall() {
        properties.setRequestsPerMinute(1);
        when(dify.isAvailable()).thenReturn(true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        actors.set(first);
        service.ask(new AssistantAskRequest("One", "vi"));
        assertThatThrownBy(() -> service.ask(new AssistantAskRequest("Two", "vi")))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.RATE_LIMITED));
        actors.set(second);
        service.ask(new AssistantAskRequest("Three", "en"));
        verify(dify).ask(first, "One", "vi", null);
        verify(dify).ask(second, "Three", "en", null);
        verify(dify, never()).ask(eq(first), eq("Two"), eq("vi"), any());
    }

    @Test
    void failedProviderCallStillConsumesAllowanceAndExcessDoesNotReachProvider() {
        properties.setRequestsPerDay(1);
        UUID user = UUID.randomUUID();
        actors.set(user);
        when(dify.isAvailable()).thenReturn(true);
        when(dify.ask(user, "Question", "vi", null)).thenThrow(new AppException(AssistantErrorCode.TIMEOUT));
        assertThatThrownBy(() -> service.ask(new AssistantAskRequest("Question", "vi")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.ask(new AssistantAskRequest("Again", "vi")))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.QUOTA_EXCEEDED));
        verify(dify, times(1)).ask(user, "Question", "vi", null);
        verify(dify, never()).ask(eq(user), eq("Again"), eq("vi"), any());
    }
}
