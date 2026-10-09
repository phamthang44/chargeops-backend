package com.thang.chargeops.assistant;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssistantService {
    private final CurrentActorContext actors;
    private final DifyClient dify;
    private final AssistantRateLimiter limiter;

    public AssistantAnswer ask(AssistantAskRequest request) {
        UUID actorId = actors.get().orElseThrow(() -> new AppException(AuthErrorCode.ACCESS_DENIED));
        String locale = request.locale() == null ? "vi" : request.locale();
        if (!StringUtils.hasText(request.question()) || request.question().length() > 2000
                || !("vi".equals(locale) || "en".equals(locale))) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        if (request.conversationId() != null && request.conversationId().length() > 64) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        if (!dify.isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        limiter.acquire(actorId);
        return dify.ask(actorId, request.question().strip(), locale, request.conversationId());
    }

    public ConversationPage getConversations(String lastId, Integer limit) {
        UUID actorId = actors.get().orElseThrow(() -> new AppException(AuthErrorCode.ACCESS_DENIED));
        if (lastId != null && lastId.length() > 64) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        if (!dify.isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        int effectiveLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        return dify.getConversations(actorId, lastId, effectiveLimit);
    }

    public MessagePage getMessages(String conversationId, String firstId, Integer limit) {
        UUID actorId = actors.get().orElseThrow(() -> new AppException(AuthErrorCode.ACCESS_DENIED));
        if (!StringUtils.hasText(conversationId) || conversationId.length() > 64) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        if (firstId != null && firstId.length() > 64) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        if (!dify.isAvailable()) { throw new AppException(AssistantErrorCode.UNAVAILABLE); }
        int effectiveLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        return dify.getMessages(actorId, conversationId, firstId, effectiveLimit);
    }
}
