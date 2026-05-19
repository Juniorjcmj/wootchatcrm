package com.seucrm.api.automation;

import com.seucrm.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/automations")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService automationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUTOMATION_ADMIN','AUTOMATION_MEMBER')")
    public ResponseEntity<List<AutomationResponse>> list() {
        return ResponseEntity.ok(automationService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUTOMATION_ADMIN')")
    public ResponseEntity<AutomationResponse> create(
        @RequestBody CreateAutomationRequest req,
        @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(automationService.create(req, user.getId()));
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN','AUTOMATION_ADMIN')")
    public ResponseEntity<AutomationResponse> toggle(@PathVariable UUID id) {
        return ResponseEntity.ok(automationService.toggle(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTOMATION_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        automationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
