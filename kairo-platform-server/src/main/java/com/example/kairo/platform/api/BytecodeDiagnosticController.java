package com.example.kairo.platform.api;

import com.example.kairo.platform.service.BytecodeDiagnosticProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/classes/{classId}")
public class BytecodeDiagnosticController {
    private final BytecodeDiagnosticProxyService service;
    private final RequestContextFactory contexts;

    public BytecodeDiagnosticController(BytecodeDiagnosticProxyService service, RequestContextFactory contexts) {
        this.service = service; this.contexts = contexts;
    }

    @GetMapping("/transformations")
    public Map<String, Object> transformations(@PathVariable String agentId, @PathVariable String classId,
                                               HttpServletRequest request) {
        return service.transformations(contexts.from(request), agentId, classId);
    }

    @GetMapping(value = "/bytecode", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> bytecode(@PathVariable String agentId, @PathVariable String classId,
                                           @RequestParam String kind, @RequestParam long revision,
                                           HttpServletRequest request) {
        byte[] bytes = service.bytecode(contexts.from(request), agentId, classId, kind, revision);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Kairo-Kind", kind.toUpperCase(java.util.Locale.ROOT))
                .header("X-Kairo-Revision", String.valueOf(revision))
                .header("X-Kairo-Size", String.valueOf(bytes.length))
                .header("X-Kairo-Hash", sha256(bytes))
                .body(bytes);
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> preview(@PathVariable String agentId, @PathVariable String classId,
                                       @RequestBody byte[] input, HttpServletRequest request) {
        return service.preview(contexts.from(request), agentId, classId, input);
    }

    @PostMapping("/capture")
    public Map<String, Object> capture(@PathVariable String agentId, @PathVariable String classId,
                                       HttpServletRequest request) {
        return service.capture(contexts.from(request), agentId, classId);
    }

    @GetMapping("/diff")
    public Map<String, Object> diff(@PathVariable String agentId, @PathVariable String classId,
                                    @RequestParam String fromKind, @RequestParam long fromRevision,
                                    @RequestParam String toKind, @RequestParam long toRevision,
                                    HttpServletRequest request) {
        return service.diff(contexts.from(request), agentId, classId,
                fromKind, fromRevision, toKind, toRevision);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
