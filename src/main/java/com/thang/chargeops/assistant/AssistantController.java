package com.thang.chargeops.assistant;

import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
@Validated
public class AssistantController {
    private final AssistantService service;

    @PostMapping("/ask")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResult<AssistantAnswer>> ask(@Valid @RequestBody AssistantAskRequest request) {
        return ResponseEntity.ok(ApiResult.success(service.ask(request)));
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResult<ConversationPage>> getConversations(
            @RequestParam(required = false) @Size(max = 64) String lastId,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) Integer limit
    ) {
        return ResponseEntity.ok(ApiResult.success(service.getConversations(lastId, limit)));
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResult<MessagePage>> getMessages(
            @PathVariable @Size(max = 64) String id,
            @RequestParam(required = false) @Size(max = 64) String firstId,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) Integer limit
    ) {
        return ResponseEntity.ok(ApiResult.success(service.getMessages(id, firstId, limit)));
    }
}
