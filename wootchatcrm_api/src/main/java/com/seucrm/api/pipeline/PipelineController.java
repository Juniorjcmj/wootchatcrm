package com.seucrm.api.pipeline;

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
@RequestMapping("/v1")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @GetMapping("/pipelines")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN','PIPELINE_MEMBER','AGENT','SUPERVISOR')")
    public ResponseEntity<List<PipelineResponse>> listPipelines() {
        return ResponseEntity.ok(pipelineService.listPipelines());
    }

    @GetMapping("/pipelines/{id}/board")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN','PIPELINE_MEMBER','AGENT','SUPERVISOR')")
    public ResponseEntity<BoardResponse> getBoard(@PathVariable UUID id) {
        return ResponseEntity.ok(pipelineService.getBoard(id));
    }

    @PostMapping("/pipelines")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN')")
    public ResponseEntity<PipelineResponse> createPipeline(@RequestBody CreatePipelineRequest req, @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.createPipeline(req, user.getId()));
    }

    @PostMapping("/deals")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN','AGENT')")
    public ResponseEntity<DealResponse> createDeal(@RequestBody CreateDealRequest req, @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.createDeal(req, user.getId()));
    }

    @PostMapping("/pipelines/{id}/stages")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN')")
    public ResponseEntity<StageResponse> createStage(@PathVariable UUID id, @RequestBody CreateStageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.createStage(id, req));
    }

    @PutMapping("/pipelines/{id}/stages/{stageId}")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN')")
    public ResponseEntity<StageResponse> updateStage(@PathVariable UUID id, @PathVariable UUID stageId, @RequestBody UpdateStageRequest req) {
        return ResponseEntity.ok(pipelineService.updateStage(id, stageId, req));
    }

    @DeleteMapping("/pipelines/{id}/stages/{stageId}")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN')")
    public ResponseEntity<Void> deleteStage(@PathVariable UUID id, @PathVariable UUID stageId) {
        pipelineService.deleteStage(id, stageId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/pipelines/{id}/stages/reorder")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN')")
    public ResponseEntity<List<StageResponse>> reorderStages(@PathVariable UUID id, @RequestBody ReorderStagesRequest req) {
        return ResponseEntity.ok(pipelineService.reorderStages(id, req.stageIds()));
    }

    @PutMapping("/deals/{id}/move")
    @PreAuthorize("hasAnyRole('ADMIN','PIPELINE_ADMIN','PIPELINE_MEMBER','AGENT')")
    public ResponseEntity<DealResponse> moveDeal(@PathVariable UUID id, @RequestBody MoveDealRequest req, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pipelineService.moveDeal(id, req.toStageId(), user.getId()));
    }
}
