package com.seucrm.api.conversation;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/v1/uploads")
@RequiredArgsConstructor
public class UploadsController {

    @GetMapping("/{filename}")
    public ResponseEntity<ByteArrayResource> serve(@PathVariable String filename) throws IOException {
        Path file = Paths.get(System.getProperty("user.dir"), "files", "uploads").resolve(filename);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        byte[] data = Files.readAllBytes(file);
        String mime = Files.probeContentType(file);
        if (mime == null) mime = "application/octet-stream";

        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }
}
