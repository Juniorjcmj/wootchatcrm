package com.seucrm.api.conversation;

import com.seucrm.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/conversations")
@RequiredArgsConstructor
public class MediaUploadController {

    private final ConversationService conversationService;

    /** URL pública (ex.: ngrok) que o Evolution Go usa pra baixar a mídia. */
    @org.springframework.beans.factory.annotation.Value("${app.whatsapp.webhook-base-url}")
    private String publicBaseUrl;

    @PostMapping(path = "/{id}/messages/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','AGENT')")
    public ResponseEntity<MessageResponse> uploadAndSend(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "caption", required = false) String caption,
            @AuthenticationPrincipal User user,
            HttpServletRequest request
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Path uploadDir = Paths.get(System.getProperty("user.dir"), "files", "uploads");
        Files.createDirectories(uploadDir);

        String original = StringUtils.cleanPath(file.getOriginalFilename());
        String ext = "";
        int i = original.lastIndexOf('.');
        if (i >= 0) ext = original.substring(i);

        String filename = UUID.randomUUID().toString() + ext;
        Path target = uploadDir.resolve(filename);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        }

        String mediaType = file.getContentType();
        if (mediaType == null) mediaType = "application/octet-stream";

        // WhatsApp aceita áudio nativamente apenas em ogg/opus (PTT - mensagem de voz).
        // Browsers gravam em webm (Chrome/Firefox) ou mp4 (Safari).
        // Transcoda pra ogg/opus antes de servir pro Evolution Go.
        String mimeBase = mediaType.split(";")[0].trim().toLowerCase();
        if (mimeBase.equals("audio/webm") || mimeBase.equals("audio/mp4")
                || mimeBase.equals("audio/aac") || mimeBase.equals("audio/x-m4a")
                || mimeBase.equals("audio/mpeg")) {
            Path ogg = transcodeAudioToOgg(target);
            if (ogg != null) {
                // Substitui o arquivo: deleta o original e adota o .ogg como novo "filename"
                try { Files.deleteIfExists(target); } catch (Exception ignore) {}
                filename  = ogg.getFileName().toString();
                target    = ogg;
                mediaType = "audio/ogg; codecs=opus";
                log.info("[UPLOAD] Áudio transcodado para ogg/opus → {}", filename);
            } else {
                log.warn("[UPLOAD] Falha no transcoding de áudio — enviando formato original {}", mediaType);
            }
        }

        // O Evolution Go roda numa VPS — não consegue acessar localhost.
        // publicBaseUrl já termina em "/api" (ex: https://xxx.ngrok-free.dev/api).
        String base = publicBaseUrl != null && !publicBaseUrl.isBlank()
                ? publicBaseUrl.replaceAll("/+$", "")
                : (request.getScheme() + "://" + request.getServerName()
                    + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "")
                    + request.getContextPath());
        String mediaUrl = base + "/v1/uploads/" + filename;
        log.info("[UPLOAD] {} → {} ({} bytes, {})", original, mediaUrl, file.getSize(), mediaType);

        // Sem caption: usa o nome original do arquivo como `content` pra preservá-lo
        // na listagem da inbox (frontend usa esse campo pra renderizar o card).
        String content = (caption != null && !caption.isBlank())
                ? caption
                : original;

        SendMessageRequest req = new SendMessageRequest(
                content,
                mediaUrl,
                mediaType
        );

        MessageResponse resp = conversationService.sendMessage(id, req, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Roda ffmpeg pra converter o áudio em ogg/opus mono 16kHz (formato esperado
     * pelo WhatsApp PTT). Retorna o Path do .ogg novo (no mesmo diretório) ou null em erro.
     */
    private Path transcodeAudioToOgg(Path input) {
        try {
            Path out = input.resolveSibling(
                input.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".ogg"
            );
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", input.toString(),
                "-vn",                          // sem vídeo
                "-c:a", "libopus",
                "-b:a", "32k",
                "-ar", "16000",
                "-ac", "1",                     // mono
                "-application", "voip",         // perfil de voz
                out.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // drena stdout pra evitar bloqueio
            try (var is = p.getInputStream(); var bo = new java.io.ByteArrayOutputStream()) {
                is.transferTo(bo);
            }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) return null;
            return out;
        } catch (Exception e) {
            log.warn("Audio transcode falhou: {}", e.getMessage());
            return null;
        }
    }
}
