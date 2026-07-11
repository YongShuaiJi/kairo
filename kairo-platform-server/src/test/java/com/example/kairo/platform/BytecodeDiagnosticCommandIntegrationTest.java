package com.example.kairo.platform;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.BytecodeDiagnosticExchange;
import com.example.kairo.platform.service.RequestContext;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BytecodeDiagnosticCommandIntegrationTest {
    @Autowired AgentCommandService commands;
    @Autowired BytecodeDiagnosticExchange exchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    @Test
    void transientBytesAreNeverPersistedInCommandPayloadOrResult() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values ('runtime-bytecode-test', 'app-default', 'env-dev', 'bytecode-test', 'localhost', '1', 'java',
                  'ACTIVE', '{}', current_timestamp, current_timestamp)
                """);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values ('agent-bytecode-test', 'runtime-bytecode-test', 'ACTIVE', 'test', 'test',
                  '127.0.0.1', 1, 'hash-only', '[]', current_timestamp, current_timestamp)
                """);
        RequestContext admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        Map<String, Object> created = commands.createBytecodeDiagnosticCommand(admin, "agent-bytecode-test",
                "BYTECODE_PREVIEW", Map.of("commandType", "BYTECODE_PREVIEW", "classId", "cid"),
                new byte[]{1, 2, 3});
        String commandId = String.valueOf(created.get("id"));
        assertThat(jdbc.queryForObject("select payload_json from agent_command where id = ?", String.class, commandId))
                .doesNotContain("bytecodeBase64Url", "AQID");

        RequestContext agent = new RequestContext("agent-bytecode-test", "corr", "127.0.0.1", "agent", "test");
        Map<String, Object> polled = commands.pollNext("agent-bytecode-test", agent, Map.of("leaseSeconds", 10));
        assertThat(((Map<?, ?>) polled.get("payload")).containsKey("bytecodeBase64Url")).isTrue();
        commands.ack(commandId, agent, Map.of("status", "ACKED",
                "result", Map.of("changed", true, "bytecodeBase64Url", "AQID")));

        assertThat(jdbc.queryForObject("select result_json from agent_command where id = ?", String.class, commandId))
                .contains("changed").doesNotContain("bytecodeBase64Url", "AQID");
        assertThat(exchange.await(commandId, Duration.ofSeconds(1)))
                .containsEntry("bytecodeBase64Url", "AQID");
        assertThat(exchange.pendingCount()).isZero();
    }
}
