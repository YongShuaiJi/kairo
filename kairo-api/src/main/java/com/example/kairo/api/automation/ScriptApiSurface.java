package com.example.kairo.api.automation;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptDiagnostic;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The script API surface an AI client must program against within an
 * {@link AutomationSession} (V1.6 &sect;4.3 "脚本 API schema、示例和结构化诊断格式").
 *
 * @param allowedProfile       effective allowed capability tier for this session
 * @param schema               JSON Schema describing the trial script input/output contract
 * @param examples             worked examples (source + expected behaviour)
 * @param diagnosticsFormat    the structured {@link ScriptDiagnostic} shape the platform returns
 * @param limits               hard limits (max hits, max ttl, forbidden APIs per tier)
 */
public record ScriptApiSurface(
        CapabilityProfile allowedProfile,
        Map<String, Object> schema,
        List<ScriptExample> examples,
        Map<String, Object> diagnosticsFormat,
        Map<String, Object> limits
) {
    public ScriptApiSurface {
        Objects.requireNonNull(allowedProfile, "allowedProfile");
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        examples = examples == null ? List.of() : List.copyOf(examples);
        diagnosticsFormat = diagnosticsFormat == null ? Map.of() : Map.copyOf(diagnosticsFormat);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public record ScriptExample(String name, String source, String description) {
        public ScriptExample {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
            description = description == null ? "" : description;
        }
    }
}
