package com.thang.chargeops.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.thang.chargeops.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class DifyClient {
    private final AssistantProperties properties;
    private final RestClient client;

    @Autowired
    public DifyClient(AssistantProperties properties) {
        this(properties, configuredBuilder(properties));
    }

    DifyClient(AssistantProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static RestClient.Builder configuredBuilder(AssistantProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory);
    }

    public boolean isAvailable() {
        return properties.isEnabled() && StringUtils.hasText(properties.getApiKey());
    }

    public AssistantAnswer ask(UUID actorId, String question, String locale) {
        return ask(actorId, question, locale, null);
    }

    public AssistantAnswer ask(UUID actorId, String question, String locale, String conversationId) {
        if (!isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        String language = "en".equals(locale) ? "English" : "Vietnamese";
        // Concise-answer contract (doc 64 §3): conclusion first, short conditions, at most one quote.
        String directive = "Please answer in " + language
                + ". Answer in under 120 words: conclusion first, then conditions/exceptions briefly, "
                + "at most one short quote from a source. Do not repeat the question or restate whole policy sections.";

        Map<String, Object> body = new HashMap<>();
        body.put("inputs", Map.of());
        body.put("query", directive + "\n\n" + question);
        body.put("response_mode", "blocking");
        body.put("auto_generate_name", false);
        body.put("user", "chargeops-" + actorId);
        if (StringUtils.hasText(conversationId)) {
            body.put("conversation_id", conversationId.strip());
        }

        try {
            DifyResponse response = client.post().uri("/chat-messages")
                    .body(body).retrieve().body(DifyResponse.class);
            if (response == null || !StringUtils.hasText(response.answer())) {
                throw new AppException(AssistantErrorCode.INVALID_RESPONSE);
            }
            List<AssistantAnswer.Citation> citations = parseCitations(
                    response.metadata() != null ? response.metadata().resources() : null);
            return new AssistantAnswer(response.answer(), locale, response.messageId(), response.conversationId(), citations);
        } catch (ResourceAccessException exception) {
            throw handleAccessException(exception);
        } catch (RestClientResponseException exception) {
            throw handleResponseException(exception);
        } catch (RestClientException exception) {
            throw new AppException(AssistantErrorCode.INVALID_RESPONSE);
        }
    }

    public ConversationPage getConversations(UUID actorId, String lastId, int limit) {
        if (!isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        String user = "chargeops-" + actorId;
        try {
            var spec = client.get().uri(uriBuilder -> {
                uriBuilder.path("/conversations")
                        .queryParam("user", user)
                        .queryParam("limit", limit)
                        .queryParam("sort_by", "-updated_at");
                if (StringUtils.hasText(lastId)) {
                    uriBuilder.queryParam("last_id", lastId.strip());
                }
                return uriBuilder.build();
            });
            DifyConversationsResponse response = spec.retrieve().body(DifyConversationsResponse.class);
            if (response == null || response.data() == null) {
                return new ConversationPage(List.of(), false, null);
            }
            List<ConversationSummary> items = response.data().stream()
                    .filter(item -> item != null && StringUtils.hasText(item.id()))
                    .map(item -> {
                        Instant createdAt = item.createdAt() != null ? Instant.ofEpochSecond(item.createdAt()) : Instant.now();
                        Instant updatedAt = item.updatedAt() != null ? Instant.ofEpochSecond(item.updatedAt()) : createdAt;
                        String title = StringUtils.hasText(item.name()) ? item.name().strip() : "Hội thoại";
                        return new ConversationSummary(item.id(), title, createdAt, updatedAt);
                    }).toList();
            boolean hasMore = Boolean.TRUE.equals(response.hasMore());
            String nextCursor = (hasMore && !items.isEmpty()) ? items.get(items.size() - 1).id() : null;
            return new ConversationPage(items, hasMore, nextCursor);
        } catch (ResourceAccessException exception) {
            throw handleAccessException(exception);
        } catch (RestClientResponseException exception) {
            throw handleResponseException(exception);
        } catch (RestClientException exception) {
            throw new AppException(AssistantErrorCode.INVALID_RESPONSE);
        }
    }

    public MessagePage getMessages(UUID actorId, String conversationId, String firstId, int limit) {
        if (!isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        String user = "chargeops-" + actorId;
        try {
            var spec = client.get().uri(uriBuilder -> {
                uriBuilder.path("/messages")
                        .queryParam("user", user)
                        .queryParam("conversation_id", conversationId.strip())
                        .queryParam("limit", limit);
                if (StringUtils.hasText(firstId)) {
                    uriBuilder.queryParam("first_id", firstId.strip());
                }
                return uriBuilder.build();
            });
            DifyMessagesResponse response = spec.retrieve().body(DifyMessagesResponse.class);
            if (response == null || response.data() == null) {
                return new MessagePage(List.of(), false, null);
            }
            List<ChatTurn> items = response.data().stream()
                    .filter(item -> item != null && StringUtils.hasText(item.id()))
                    .map(item -> {
                        Instant createdAt = item.createdAt() != null ? Instant.ofEpochSecond(item.createdAt()) : Instant.now();
                        String cleanQuery = stripDirective(item.query());
                        List<AssistantAnswer.Citation> citations = parseCitations(item.retrieverResources());
                        return new ChatTurn(item.id(), item.conversationId(), cleanQuery, item.answer(),
                                item.status() != null ? item.status() : "normal", createdAt, citations);
                    }).toList();
            boolean hasMore = Boolean.TRUE.equals(response.hasMore());
            String nextCursor = (hasMore && !items.isEmpty()) ? items.get(0).id() : null;
            return new MessagePage(items, hasMore, nextCursor);
        } catch (ResourceAccessException exception) {
            throw handleAccessException(exception);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new AppException(AssistantErrorCode.CONVERSATION_NOT_FOUND);
            }
            throw handleResponseException(exception);
        } catch (RestClientException exception) {
            throw new AppException(AssistantErrorCode.INVALID_RESPONSE);
        }
    }

    static String stripDirective(String query) {
        if (query == null) return "";
        int idx = query.indexOf("\n\n");
        if (idx >= 0 && query.startsWith("Please answer in ")) {
            return query.substring(idx + 2).strip();
        }
        return query.strip();
    }

    static List<AssistantAnswer.Citation> parseCitations(List<Resource> resources) {
        if (resources == null) return List.of();
        return resources.stream()
                .filter(resource -> resource != null && StringUtils.hasText(resource.documentName()))
                .limit(20)
                .map(resource -> new AssistantAnswer.Citation(resource.documentId(), resource.documentName(),
                        resource.segmentId(), resource.content(), resource.score()))
                .toList();
    }

    private static AppException handleAccessException(ResourceAccessException exception) {
        boolean timeout = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                timeout = true;
                break;
            }
        }
        return new AppException(timeout ? AssistantErrorCode.TIMEOUT : AssistantErrorCode.UNAVAILABLE);
    }

    private static AppException handleResponseException(RestClientResponseException exception) {
        String code = upstreamErrorCode(exception.getResponseBodyAsString());
        if (exception.getStatusCode().value() == 402 || "provider_quota_exceeded".equals(code)
                || "rate_limit_error".equals(code)) {
            return new AppException(AssistantErrorCode.UPSTREAM_QUOTA_EXCEEDED);
        }
        if (exception.getStatusCode().value() == 429) {
            return new AppException(AssistantErrorCode.PROVIDER_RATE_LIMITED);
        }
        return new AppException(AssistantErrorCode.UNAVAILABLE);
    }

    private static String upstreamErrorCode(String body) {
        if (body == null || body.length() > 8192) { return ""; }
        try { return JsonMapper.builder().build().readTree(body).path("code").asText(); }
        catch (RuntimeException ignored) { return ""; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyResponse(
            String answer,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("conversation_id") String conversationId,
            Metadata metadata
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(@JsonProperty("retriever_resources") List<Resource> resources) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("document_name") String documentName,
            @JsonProperty("segment_id") String segmentId,
            String content,
            Double score
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyConversationsResponse(
            Integer limit,
            @JsonProperty("has_more") Boolean hasMore,
            List<DifyConversationItem> data
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyConversationItem(
            String id,
            String name,
            Map<String, Object> inputs,
            String status,
            @JsonProperty("created_at") Long createdAt,
            @JsonProperty("updated_at") Long updatedAt
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyMessagesResponse(
            Integer limit,
            @JsonProperty("has_more") Boolean hasMore,
            List<DifyMessageItem> data
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyMessageItem(
            String id,
            @JsonProperty("conversation_id") String conversationId,
            Map<String, Object> inputs,
            String query,
            String answer,
            String status,
            @JsonProperty("retriever_resources") List<Resource> retrieverResources,
            @JsonProperty("created_at") Long createdAt
    ) { }
}
