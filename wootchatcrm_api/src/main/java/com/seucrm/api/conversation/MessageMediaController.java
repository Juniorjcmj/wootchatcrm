package com.seucrm.api.conversation;

import com.seucrm.config.TenantContext;
import com.seucrm.domain.conversation.Message;
import com.seucrm.domain.conversation.MessageRepository;
import com.seucrm.shared.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

/**
 * Serve a mídia anexada a uma mensagem (áudio, imagem, ...).
 * O Evolution Go entrega o blob decriptado em `data.Message.base64`; armazenamos
 * em `messages.media_base64` e devolvemos aqui sob demanda.
 *
 * Compatibilidade: Safari não toca ogg/opus nativamente. Quando o User-Agent indica
 * Safari (ou se o cliente pediu `?format=m4a`), transcodamos ogg → m4a (AAC) com
 * ffmpeg. O resultado é cacheado em /tmp para futuras requisições.
 */
@Slf4j
@RestController
@RequestMapping("/v1/messages")
@RequiredArgsConstructor
public class MessageMediaController {

    private final MessageRepository messageRepo;

    @GetMapping("/{id}/media")
    public ResponseEntity<byte[]> downloadMedia(
            @PathVariable UUID id,
            @RequestParam(value = "format", required = false) String format,
            HttpServletRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.get());

        Message msg = messageRepo.findById(id)
                .filter(m -> tenantId.equals(m.getTenantId()))
                .orElseThrow(() -> BusinessException.notFound("Message", id));

        String b64 = msg.getMediaBase64();
        if (b64 == null || b64.isBlank()) {
            return ResponseEntity.noContent().build();
        }

        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);

        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException e) { bytes = Base64.getMimeDecoder().decode(b64); }

        String mime = (msg.getMediaMime() != null && !msg.getMediaMime().isBlank())
                ? msg.getMediaMime().split(";")[0].trim()
                : "application/octet-stream";

        // Safari não toca ogg/opus → transcoda pra m4a (AAC)
        String ua = req.getHeader("User-Agent");
        boolean isSafari = ua != null && ua.contains("Safari") && !ua.contains("Chrome") && !ua.contains("Chromium");
        boolean wantM4a  = "m4a".equalsIgnoreCase(format) || "aac".equalsIgnoreCase(format);
        if ("audio/ogg".equals(mime) && (isSafari || wantM4a)) {
            try {
                bytes = transcodeOggToM4a(id, bytes);
                mime = "audio/mp4";
            } catch (Exception e) {
                log.warn("Falha ao transcodar mensagem {} para m4a: {} — devolvendo ogg original", id, e.getMessage());
            }
        }

        MediaType ct;
        try { ct = MediaType.parseMediaType(mime); }
        catch (Exception e) { ct = MediaType.APPLICATION_OCTET_STREAM; }

        HttpHeaders h = new HttpHeaders();
        h.setContentType(ct);
        h.setContentLength(bytes.length);
        h.setCacheControl("private, max-age=3600");
        return new ResponseEntity<>(bytes, h, org.springframework.http.HttpStatus.OK);
    }

    // ── Transcode ogg → m4a com ffmpeg, com cache em /tmp ──────
    private byte[] transcodeOggToM4a(UUID messageId, byte[] oggBytes) throws IOException, InterruptedException {
        Path cache = Paths.get(System.getProperty("java.io.tmpdir"), "crm-media-" + messageId + ".m4a");
        if (Files.exists(cache) && Files.size(cache) > 0) {
            return Files.readAllBytes(cache);
        }

        Path inOgg = Paths.get(System.getProperty("java.io.tmpdir"), "crm-in-" + messageId + ".ogg");
        Files.write(inOgg, oggBytes);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inOgg.toString(),
                "-c:a", "aac", "-b:a", "96k",
                "-movflags", "+faststart",
                cache.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            is.transferTo(bo); // drena o stdout pra evitar bloqueio
        }
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg timeout");
        }
        if (p.exitValue() != 0) throw new IOException("ffmpeg exit code " + p.exitValue());

        Files.deleteIfExists(inOgg);
        return Files.readAllBytes(cache);
    }
}
