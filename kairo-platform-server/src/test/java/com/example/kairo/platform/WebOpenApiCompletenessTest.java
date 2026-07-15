package com.example.kairo.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;6 / &sect;9: the Platform half of automatic Web&harr;OpenAPI verification.
 * Reads the Web's authoritative {@code kairo-platform-web/lib/api/paths.ts} registry
 * and proves every operation it declares is present in the live OpenAPI document at
 * {@code /v3/api-docs}. Together with the Web's
 * {@code tests/web-openapi-completeness.test.ts} (which proves every Web callsite is
 * covered by the registry), this gives exhaustive Web&harr;OpenAPI coverage with no
 * hand-maintained report.
 *
 * <p>Matching is structural (a {@code {param}} matches any segment), so the check is
 * not brittle about path-variable names.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_web_openapi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebOpenApiCompletenessTest {

    @Autowired MockMvc mockMvc;

    private static final Pattern ENTRY = Pattern.compile(
            "\\{\\s*method:\\s*\"(\\w+)\"\\s*,\\s*path:\\s*\"([^\"]+)\"");

    private static String webRegistrySource() throws Exception {
        List<Path> candidates = List.of(
                Path.of("../kairo-platform-web/lib/api/paths.ts"),     // module cwd
                Path.of("kairo-platform-web/lib/api/paths.ts"));        // repo-root cwd
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IllegalStateException(
                "Cannot find kairo-platform-web/lib/api/paths.ts from " + Path.of("").toAbsolutePath());
    }

    private static List<String> structural(String path) {
        List<String> segs = new ArrayList<>();
        for (String seg : path.split("/")) {
            segs.add(seg.startsWith("{") && seg.endsWith("}") ? "{}" : seg);
        }
        return segs;
    }

    private static boolean segmentsMatch(String a, String b) {
        return a.equals(b) || a.equals("{}") || b.equals("{}");
    }

    @Test
    void everyWebRegistryOperationExistsInOpenApi() throws Exception {
        Matcher matcher = ENTRY.matcher(webRegistrySource());
        List<String[]> registry = new ArrayList<>();
        while (matcher.find()) {
            registry.add(new String[]{matcher.group(1), matcher.group(2)});
        }
        assertThat(registry)
                .as("paths.ts must declare a non-trivial registry of Web operations")
                .hasSizeGreaterThan(20);

        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode doc = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        JsonNode paths = doc.get("paths");
        assertThat(paths).isNotNull();

        // Pre-compute the live OpenAPI operations as (method, structural-segments).
        List<String[]> liveOps = new ArrayList<>();
        Iterator<String> pathIt = paths.fieldNames();
        while (pathIt.hasNext()) {
            String openApiPath = pathIt.next();
            String relative = openApiPath.startsWith("/api/v1/")
                    ? openApiPath.substring("/api/v1/".length()) : null;
            if (relative == null) continue;
            List<String> segs = structural(relative);
            JsonNode pathNode = paths.get(openApiPath);
            Iterator<String> methodIt = pathNode.fieldNames();
            while (methodIt.hasNext()) {
                String method = methodIt.next();
                if (List.of("get", "post", "put", "patch", "delete", "head", "options", "trace")
                        .contains(method)) {
                    liveOps.add(new String[]{method.toUpperCase(), String.join("/", segs)});
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String[] entry : registry) {
            String method = entry[0].toUpperCase();
            List<String> regSegs = structural(entry[1]);
            boolean found = liveOps.stream().anyMatch(live -> {
                if (!live[0].equals(method)) return false;
                List<String> liveSegs = List.of(live[1].split("/"));
                if (liveSegs.size() != regSegs.size()) return false;
                for (int i = 0; i < regSegs.size(); i++) {
                    if (!segmentsMatch(regSegs.get(i), liveSegs.get(i))) return false;
                }
                return true;
            });
            if (!found) {
                missing.add(method + " /api/v1/" + entry[1]);
            }
        }
        assertThat(missing)
                .as("registry operations not present in the live OpenAPI document")
                .isEmpty();
    }
}
