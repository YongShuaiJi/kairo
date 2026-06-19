package com.example.runtimemock.platform.api;

import com.example.runtimemock.platform.recording.RecordingIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/recording-sessions")
@ConditionalOnProperty(prefix = "runtime-mock.platform.recording.ingestion",
        name = "enabled", havingValue = "true")
public class RecordingController {

    private final RecordingIngestionService ingestionService;
    private final RequestContextFactory requestContextFactory;

    public RecordingController(RecordingIngestionService ingestionService,
                               RequestContextFactory requestContextFactory) {
        this.ingestionService = ingestionService;
        this.requestContextFactory = requestContextFactory;
    }

    @PostMapping("/{id}/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> ingest(
            @PathVariable String id,
            HttpServletRequest httpRequest,
            @Valid @RequestBody Map<String, Object> request
    ) {
        return ingestionService.ingest(id, requestContextFactory.from(httpRequest), request);
    }
}
