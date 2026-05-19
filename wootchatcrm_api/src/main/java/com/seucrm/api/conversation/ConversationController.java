package com.seucrm.api.conversation;

import com.seucrm.domain.conversation.ConversationStatus;
import com.seucrm.shared.pagination.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','AGENT')")
    public ResponseEntity<PageResponse<ConversationSummary>> list(
        @RequestParam(required = false) ConversationStatus status,
        @RequestParam(required = false) UUID assignedTo,
        @RequestParam(required = false) UUID connectionId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(conversationService.list(status, assignedTo, connectionId, page, size));
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','AGENT')")
    public ResponseEntity<PageResponse<MessageResponse>> getMessages(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(conversationService.getMessages(id, page, size));
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','AGENT')")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable UUID id, @RequestBody SendMessageRequest req, @AuthenticationPrincipal com.seucrm.domain.user.User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.sendMessage(id, req, user));
    }

    @PutMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<ConversationSummary> assign(@PathVariable UUID id, @RequestBody AssignRequest req) {
        return ResponseEntity.ok(conversationService.assign(id, req.userId()));
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','AGENT')")
    public ResponseEntity<ConversationSummary> finish(@PathVariable UUID id, @AuthenticationPrincipal com.seucrm.domain.user.User user) {
        return ResponseEntity.ok(conversationService.finish(id, user.getId()));
    }
}
