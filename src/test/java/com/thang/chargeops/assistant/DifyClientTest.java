package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DifyClientTest {
    private static final String VI_DIRECTIVE = "Please answer in Vietnamese. Answer in under 120 words: "
            + "conclusion first, then conditions/exceptions briefly, at most one short quote from a source. "
            + "Do not repeat the question or restate whole policy sections.";
    private static final String EN_DIRECTIVE = "Please answer in English. Answer in under 120 words: "
            + "conclusion first, then conditions/exceptions briefly, at most one short quote from a source. "
            + "Do not repeat the question or restate whole policy sections.";
    private final AssistantProperties properties = configured();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DifyClient client = new DifyClient(properties, builder);
    private final UUID user = UUID.fromString("00000000-0000-0000-0000-000000000067");

    @Test
    void sendsServerIdentityAndMapsCitationsWithoutForwardingConversationOrCosts() {
        server.expect(requestTo("https://api.dify.ai/v1/chat-messages"))
                .andExpect(header("Authorization", "Bearer test-app-key"))
                .andExpect(content().json("""
                        {"inputs":{},"query":"%s\n\nĐúng hạn?",
                         "user":"chargeops-00000000-0000-0000-0000-000000000067",
                         "response_mode":"blocking","auto_generate_name":false}
                        """.formatted(VI_DIRECTIVE), true))
                .andRespond(withSuccess("""
                        {"answer":"Hoàn 0%.","message_id":"message-1","conversation_id":"upstream-only",
                        "metadata":{"usage":{"total_price":"1"},"retriever_resources":[
                          {"document_id":"doc-1","document_name":"cancellation-and-refunds.vi.md",
                           "segment_id":"CR-02","content":"Đúng hạn hoàn 0%.","score":0.98}]}}
                        """, MediaType.APPLICATION_JSON));
        AssistantAnswer answer = client.ask(user, "Đúng hạn?", "vi");
        assertThat(answer.answer()).isEqualTo("Hoàn 0%.");
        assertThat(answer.messageId()).isEqualTo("message-1");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().getFirst().documentName()).isEqualTo("cancellation-and-refunds.vi.md");
        assertThat(answer.citations().getFirst().segmentId()).isEqualTo("CR-02");
        server.verify();
    }

    @Test
    void canAnswerEnglishWithoutCitations() {
        server.expect(anything()).andExpect(jsonPath("$.query").value(EN_DIRECTIVE + "\n\nUnknown?"))
                .andRespond(withSuccess("{\"answer\":\"I do not have that information.\"}", MediaType.APPLICATION_JSON));
        assertThat(client.ask(user, "Unknown?", "en").citations()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void upstreamErrorsAreSanitizedAndNotRetried(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
                .body("secret upstream detail test-app-key"));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(status == 429
                            ? AssistantErrorCode.PROVIDER_RATE_LIMITED : AssistantErrorCode.UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("test-app-key", "secret");
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider_quota_exceeded", "rate_limit_error"})
    void documentedDifyQuotaCodesAreReportedWithoutLeakingProviderMessage(String code) {
        server.expect(anything()).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\",\"message\":\"secret test-app-key\"}"));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.UPSTREAM_QUOTA_EXCEEDED);
                    assertThat(error.getMessage()).doesNotContain("secret", "test-app-key");
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void paymentRequiredIsReportedAsUpstreamQuotaWithoutRetry() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.UPSTREAM_QUOTA_EXCEEDED));
        server.verify();
    }

    @Test
    void opaqueWrappedPluginErrorIsNotMisclassifiedFromMessageText() {
        server.expect(anything()).andRespond(withServerError().body("{\"message\":\"PluginInvokeError: quota exceeded secret\"}"));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.UNAVAILABLE));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"answer\":\" \"}", "not-json"})
    void rejectsMissingBlankOrMalformedAnswers(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.INVALID_RESPONSE));
        server.verify();
    }

    @Test
    void timeoutIsMappedToGatewayTimeout() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("upstream details")));
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.TIMEOUT));
        server.verify();
    }

    @Test
    void disabledConfigurationMakesNoNetworkCall() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> client.ask(user, "Question", "vi"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.UNAVAILABLE));
        server.verify();
    }

    @Test
    void askWithConversationIdForwardsConversationId() {
        server.expect(requestTo("https://api.dify.ai/v1/chat-messages"))
                .andExpect(header("Authorization", "Bearer test-app-key"))
                .andExpect(jsonPath("$.conversation_id").value("conv-xyz"))
                .andRespond(withSuccess("""
                        {"answer":"Answer.","message_id":"msg-2","conversation_id":"conv-xyz"}
                        """, MediaType.APPLICATION_JSON));
        AssistantAnswer answer = client.ask(user, "Câu hỏi?", "vi", "conv-xyz");
        assertThat(answer.conversationId()).isEqualTo("conv-xyz");
        assertThat(answer.answer()).isEqualTo("Answer.");
        server.verify();
    }

    @Test
    void getConversationsParsesItemsAndNextCursor() {
        server.expect(requestTo("https://api.dify.ai/v1/conversations?user=chargeops-00000000-0000-0000-0000-000000000067&limit=10&sort_by=-updated_at&last_id=conv-0"))
                .andExpect(header("Authorization", "Bearer test-app-key"))
                .andRespond(withSuccess("""
                        {
                          "limit": 10,
                          "has_more": true,
                          "data": [
                            {"id":"conv-1","name":"Chính sách hoàn tiền","created_at":1728470000,"updated_at":1728470100},
                            {"id":"conv-2","name":"","created_at":1728460000,"updated_at":1728460000}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        ConversationPage page = client.getConversations(user, "conv-0", 10);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo("conv-1");
        assertThat(page.items().get(0).title()).isEqualTo("Chính sách hoàn tiền");
        assertThat(page.items().get(1).title()).isEqualTo("Hội thoại");
        assertThat(page.nextCursor()).isEqualTo("conv-2");
        server.verify();
    }

    @Test
    void getMessagesStripsDirectiveAndParsesCitations() {
        server.expect(requestTo("https://api.dify.ai/v1/messages?user=chargeops-00000000-0000-0000-0000-000000000067&conversation_id=conv-1&limit=10&first_id=msg-0"))
                .andExpect(header("Authorization", "Bearer test-app-key"))
                .andRespond(withSuccess("""
                        {
                          "limit": 10,
                          "has_more": false,
                          "data": [
                            {
                              "id": "msg-1",
                              "conversation_id": "conv-1",
                              "query": "Please answer in Vietnamese. Answer in under 120 words: conclusion first, then conditions/exceptions briefly, at most one short quote from a source. Do not repeat the question or restate whole policy sections.\\n\\nKhách có được hoàn tiền không?",
                              "answer": "Có, hoàn 100%.",
                              "status": "normal",
                              "created_at": 1728470000,
                              "retriever_resources": [
                                {"document_id":"doc-1","document_name":"cancellation.vi.md","segment_id":"S-1","content":"Hoàn 100%","score":0.9}
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        MessagePage page = client.getMessages(user, "conv-1", "msg-0", 10);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).hasSize(1);
        ChatTurn turn = page.items().get(0);
        assertThat(turn.id()).isEqualTo("msg-1");
        assertThat(turn.query()).isEqualTo("Khách có được hoàn tiền không?");
        assertThat(turn.answer()).isEqualTo("Có, hoàn 100%.");
        assertThat(turn.citations()).hasSize(1);
        assertThat(turn.citations().get(0).documentName()).isEqualTo("cancellation.vi.md");
        server.verify();
    }

    @Test
    void getMessagesThrowsNotFoundOn404() {
        server.expect(requestTo("https://api.dify.ai/v1/messages?user=chargeops-00000000-0000-0000-0000-000000000067&conversation_id=conv-unknown&limit=20"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getMessages(user, "conv-unknown", null, 20))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AssistantErrorCode.CONVERSATION_NOT_FOUND));
        server.verify();
    }

    private static AssistantProperties configured() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-app-key");
        return properties;
    }
}
