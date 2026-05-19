package com.seucrm.api.lead;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.lead.*;
import com.seucrm.domain.user.User;
import com.seucrm.shared.exception.BusinessException;
import com.seucrm.shared.pagination.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadRepository leadRepository;
    private final TagRepository tagRepository;
    private final LeadNoteRepository noteRepository;
    private final com.seucrm.domain.pipeline.DealRepository dealRepository;

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> list(int page, int size, String sort) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort != null ? sort : "createdAt"));
        return PageResponse.of(
            leadRepository.findByTenantId(tenantId, pageable)
                .map(LeadResponse::from));
    }

    @Transactional(readOnly = true)
    public LeadResponse findById(UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Lead lead = leadRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", id));
        return LeadResponse.from(lead);
    }

    @Transactional
    public LeadResponse create(CreateLeadRequest req, UUID createdBy) {
        UUID tenantId = UUID.fromString(TenantContext.get());

        if (req.phone() != null && leadRepository.existsByTenantIdAndPhone(tenantId, req.phone())) {
            throw BusinessException.conflict("Lead with this phone already exists");
        }

        Lead lead = Lead.builder()
            .tenantId(tenantId)
            .name(req.name())
            .phone(req.phone())
            .email(req.email())
            .website(req.website())
            .document(req.document())
            .birthdate(req.birthdate())
            .channel(req.channel() != null ? req.channel() : LeadChannel.MANUAL)
            .origin(req.origin())
            .assignedTo(req.assignedTo())
            .createdBy(createdBy)
            .build();

        return LeadResponse.from(leadRepository.save(lead));
    }

    @Transactional
    public LeadResponse update(UUID id, UpdateLeadRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Lead lead = leadRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", id));

        if (req.name() != null) lead.setName(req.name());
        if (req.phone() != null) lead.setPhone(req.phone());
        if (req.email() != null) lead.setEmail(req.email());
        if (req.assignedTo() != null) lead.setAssignedTo(req.assignedTo());
        if (req.origin() != null) lead.setOrigin(req.origin());

        return LeadResponse.from(leadRepository.save(lead));
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Lead lead = leadRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", id));
        lead.setActive(false);
        leadRepository.save(lead);
    }

    // ── Tags ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<TagInfo> listLeadTags(UUID leadId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Lead lead = leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));
        return lead.getTags().stream().map(TagInfo::from).toList();
    }

    /** Find-or-create a tag por (tenant, name) e associa ao lead. */
    @Transactional
    public java.util.List<TagInfo> addTag(UUID leadId, String name, String color) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        if (name == null || name.isBlank()) {
            throw BusinessException.validation("Tag name is required");
        }
        Lead lead = leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));

        String safeName = name.trim();
        Tag tag = tagRepository.findByTenantIdAndName(tenantId, safeName)
            .orElseGet(() -> tagRepository.save(Tag.builder()
                .tenantId(tenantId)
                .name(safeName)
                .color(color != null && !color.isBlank() ? color : "#5e6ad2")
                .build()));

        lead.getTags().add(tag);
        leadRepository.save(lead);
        return lead.getTags().stream().map(TagInfo::from).toList();
    }

    @Transactional
    public java.util.List<TagInfo> removeTag(UUID leadId, String tagName) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Lead lead = leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));
        lead.getTags().removeIf(t -> t.getName().equalsIgnoreCase(tagName));
        leadRepository.save(lead);
        return lead.getTags().stream().map(TagInfo::from).toList();
    }

    // ── Notas internas ───────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<NoteResponse> listNotes(UUID leadId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));
        return noteRepository.findByLeadIdOrderByCreatedAtDesc(leadId)
            .stream().map(NoteResponse::from).toList();
    }

    // ── Deals (negócios) do lead ─────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<LeadDealInfo> listDeals(UUID leadId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));
        return dealRepository.findByTenantIdAndLeadIdOrderByEnteredStageAtDesc(tenantId, leadId)
            .stream()
            .map(d -> new LeadDealInfo(
                d.getId(),
                d.getTitle(),
                d.getValue(),
                d.getPipeline() != null ? d.getPipeline().getName() : null,
                d.getStage()    != null ? d.getStage().getName()    : null,
                d.getStatus() != null ? d.getStatus().name() : null,
                d.getEnteredStageAt()
            ))
            .toList();
    }

    @Transactional
    public NoteResponse addNote(UUID leadId, String content, UUID userId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        leadRepository.findByIdAndTenantId(leadId, tenantId)
            .orElseThrow(() -> BusinessException.notFound("Lead", leadId));
        if (content == null || content.isBlank()) {
            throw BusinessException.validation("Note content is required");
        }
        LeadNote note = noteRepository.save(LeadNote.builder()
            .leadId(leadId)
            .tenantId(tenantId)
            .userId(userId)
            .content(content.trim())
            .build());
        return NoteResponse.from(note);
    }
}
