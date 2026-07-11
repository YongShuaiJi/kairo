package com.example.kairo.platform;

import com.example.kairo.platform.service.BytecodeMetadataService;
import com.example.kairo.platform.service.BytecodeMetadataService.BytecodeMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BytecodeMetadataIntegrationTest {
    @Autowired BytecodeMetadataService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void upsertIsIdempotentAndClassLoaderIsPartOfIdentity() {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        service.upsert(metadata("loader-a", "STARTED", "hash-a", now));
        service.upsert(metadata("loader-a", "SUCCEEDED", "hash-b", now));
        service.upsert(metadata("loader-b", "SUCCEEDED", "hash-c", now));
        assertThat(service.findByClassIdentity("runtime-1", "example.Service", "loader-a"))
                .singleElement().satisfies(row -> {
                    assertThat(row.transformationStatus()).isEqualTo("SUCCEEDED");
                    assertThat(row.bytecodeHash()).isEqualTo("hash-b");
                });
        assertThat(service.findByClassIdentity("runtime-1", "example.Service", "loader-b"))
                .singleElement().extracting(BytecodeMetadata::bytecodeHash).isEqualTo("hash-c");
        assertThat(jdbc.queryForObject("select count(*) from bytecode_transformation_metadata", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void schemaContainsNoBinaryPayloadColumn() {
        var columns = jdbc.queryForList("select column_name, data_type from information_schema.columns "
                + "where table_name = 'bytecode_transformation_metadata'");
        assertThat(columns).isNotEmpty();
        assertThat(columns).allSatisfy(column -> {
            String name = String.valueOf(column.get("column_name")).toLowerCase();
            String type = String.valueOf(column.get("data_type")).toLowerCase();
            assertThat(name).isNotIn("class_bytes", "bytecode_bytes", "blob", "payload");
            assertThat(type).doesNotContain("binary", "blob", "bytea");
        });
    }

    private static BytecodeMetadata metadata(String loader, String status, String hash, Timestamp now) {
        return new BytecodeMetadata("runtime-1", "agent-1", "example.Service", loader,
                1L, "INPUT", hash, 128L, status, "[]", now, now, now);
    }
}
