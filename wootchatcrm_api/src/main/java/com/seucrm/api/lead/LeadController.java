package com.seucrm.api.lead;

import com.seucrm.domain.user.User;
import com.seucrm.shared.pagination.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','LEADS_MEMBER','SUPERVISOR','AGENT')")
    public ResponseEntity<PageResponse<LeadResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort
    ) {
        return ResponseEntity.ok(leadService.list(page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','LEADS_MEMBER','SUPERVISOR','AGENT')")
    public ResponseEntity<LeadResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','AGENT')")
    public ResponseEntity<LeadResponse> create(
        @Valid @RequestBody CreateLeadRequest req,
        @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(leadService.create(req, user.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','AGENT')")
    public ResponseEntity<LeadResponse> update(
        @PathVariable UUID id,
        @RequestBody UpdateLeadRequest req
    ) {
        return ResponseEntity.ok(leadService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        leadService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Tags ─────────────────────────────────────────────────

    @GetMapping("/{id}/tags")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','LEADS_MEMBER','SUPERVISOR','AGENT')")
    public ResponseEntity<java.util.List<TagInfo>> listTags(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.listLeadTags(id));
    }

    @PostMapping("/{id}/tags")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','AGENT')")
    public ResponseEntity<java.util.List<TagInfo>> addTag(
            @PathVariable UUID id,
            @RequestBody AddTagRequest req) {
        return ResponseEntity.ok(leadService.addTag(id, req.name(), req.color()));
    }

    @DeleteMapping("/{id}/tags/{tagName}")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','AGENT')")
    public ResponseEntity<java.util.List<TagInfo>> removeTag(
            @PathVariable UUID id,
            @PathVariable String tagName) {
        return ResponseEntity.ok(leadService.removeTag(id, tagName));
    }

    // ── Notas internas ───────────────────────────────────────

    @GetMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','LEADS_MEMBER','SUPERVISOR','AGENT')")
    public ResponseEntity<java.util.List<NoteResponse>> listNotes(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.listNotes(id));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','AGENT')")
    public ResponseEntity<NoteResponse> addNote(
            @PathVariable UUID id,
            @RequestBody AddNoteRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(leadService.addNote(id, req.content(), user != null ? user.getId() : null));
    }

    // ── Deals (negócios) do lead ─────────────────────────────

    @GetMapping("/{id}/deals")
    @PreAuthorize("hasAnyRole('ADMIN','LEADS_ADMIN','LEADS_MEMBER','SUPERVISOR','AGENT')")
    public ResponseEntity<java.util.List<LeadDealInfo>> listDeals(@PathVariable UUID id) {
        return ResponseEntity.ok(leadService.listDeals(id));
    }
}

record AddTagRequest(String name, String color) {}
record AddNoteRequest(String content) {}

// Resposta mínima dos negócios pra a inbox/lead detail (sem dependência cruzada de package)
record LeadDealInfo(
        java.util.UUID id,
        String title,
        java.math.BigDecimal value,
        String pipelineName,
        String stageName,
        String status,
        java.time.Instant enteredStageAt) {}
