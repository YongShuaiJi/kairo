package com.example.runtimemock.platform.worker;

import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "runtime-mock.platform.extraction.worker",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionWorker {

    private static final Pattern NAMED_PARAMETER = Pattern.compile(":[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?");
    private static final Pattern SAFE_WHERE = Pattern.compile(
            "[A-Za-z0-9_$.:?(),=<>!+*/%\\-\\s'\"\\[\\]]+");
    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?i)(;|--|/\\*|\\*/|\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|execute|copy|lock|vacuum|analyze|refresh)\\b)");
    private static final int MAX_EXTRACTION_ROWS = 100_000;
    private static final int MAX_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformJdbcService eventWriter;
    private final LocalObjectStore objectStore;
    private final Clock clock;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public ExtractionWorker(JdbcTemplate jdbcTemplate, PlatformJdbcService eventWriter,
                            LocalObjectStore objectStore,
                            @Value("${runtime-mock.platform.extraction.worker.batch-size:5}") int batchSize) {
        this(jdbcTemplate, eventWriter, objectStore, Clock.systemUTC(), batchSize);
    }

    ExtractionWorker(JdbcTemplate jdbcTemplate, PlatformJdbcService eventWriter,
                     LocalObjectStore objectStore, Clock clock, int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventWriter = eventWriter;
        this.objectStore = objectStore;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${runtime-mock.platform.extraction.worker.fixed-delay-ms:5000}",
            initialDelayString = "${runtime-mock.platform.extraction.worker.fixed-delay-ms:5000}")
    public void scheduledRun() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce(systemContext());
        } finally {
            running.set(false);
        }
    }

    @Transactional
    public Map<String, Object> runOnce(RequestContext context) {
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> tasks = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from extraction_task
                 where status = 'QUEUED'
                 order by updated_at, id
                 limit ?
                """, batchSize));
        for (Map<String, Object> task : tasks) {
            processed++;
            try {
                processTask(context, task);
                succeeded++;
            } catch (Exception e) {
                failTask(context, task, e);
                failed++;
            }
        }
        return Map.of("processed", processed, "succeeded", succeeded, "failed", failed);
    }

    private void processTask(RequestContext context, Map<String, Object> task) throws Exception {
        String taskId = String.valueOf(task.get("id"));
        long runningVersion = ((Number) task.get("version")).longValue() + 1;
        Instant now = clock.instant();
        int claimed = jdbcTemplate.update("""
                update extraction_task
                   set status = 'RUNNING', version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'QUEUED'
                """, runningVersion, context.actor(), timestamp(now), taskId);
        if (claimed == 0) {
            return;
        }
        String executionId = "extraction-execution-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into extraction_execution(
                    id, extraction_task_id, worker_id, status, started_at, finished_at, metrics_json, error_message
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId, taskId, context.actor(), "RUNNING", timestamp(now), null,
                PlatformJson.write(Map.of()), null);
        Map<String, Object> materialized = materialize(taskId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) materialized.get("rows");
        LocalObjectStore.StoredObject object = objectStore.putJson("extraction", "extraction_task", taskId,
                "ROWS_JSON", rows, Map.of("rowCount", rows.size(), "source", materialized.get("source")));
        jdbcTemplate.update("""
                insert into extraction_result(
                    id, extraction_task_id, result_type, object_uri, row_count, bytes_count, content_hash, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, "extraction-result-" + UUID.randomUUID(), taskId, "ROWS_JSON", object.objectUri(),
                rows.size(), object.bytesCount(), object.contentHash(), timestamp(clock.instant()));
        Map<String, Object> metrics = Map.of(
                "rowCount", rows.size(),
                "bytesCount", object.bytesCount(),
                "contentHash", object.contentHash(),
                "source", materialized.get("source"));
        jdbcTemplate.update("""
                update extraction_execution
                   set status = 'SUCCEEDED', finished_at = ?, metrics_json = ?
                 where id = ?
                """, timestamp(clock.instant()), PlatformJson.write(metrics), executionId);
        long completedVersion = runningVersion + 1;
        int completed = jdbcTemplate.update("""
                update extraction_task
                   set status = 'SUCCEEDED', version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, completedVersion, context.actor(), timestamp(clock.instant()), taskId, runningVersion);
        if (completed == 0) {
            throw new IllegalStateException("Extraction task state changed while worker was running: " + taskId);
        }
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from extraction_task where id = ?", taskId));
        eventWriter.recordEvent(context, "extraction_task.worker_succeeded", "extraction_task", taskId,
                completedVersion, task, updated, "SUCCESS", "extraction worker completed",
                metrics);
    }

    private Map<String, Object> materialize(String taskId) throws Exception {
        Map<String, Object> task = normalizeRow(jdbcTemplate.queryForMap("""
                select t.*, v.root_table, v.template_json, d.datasource_type, d.config_json,
                       d.application_id, d.environment_id, q.max_rows, q.timeout_seconds
                  from extraction_task t
                  join extraction_template_version v
                    on v.template_id = t.template_id and v.version = t.template_version
                  join extraction_template et
                    on et.id = t.template_id
                  join datasource_registration d
                    on d.id = et.datasource_id
                  left join extraction_quota q
                    on q.extraction_task_id = t.id
                 where t.id = ?
                """, taskId));
        Map<String, Object> template = PlatformJson.readMap(String.valueOf(task.get("template_json")));
        Map<String, Object> datasource = PlatformJson.readMap(String.valueOf(task.get("config_json")));
        Map<String, Object> parameters = PlatformJson.readMap(String.valueOf(task.get("parameters_json")));
        if ("TEST_FIXTURE".equals(String.valueOf(task.get("datasource_type")))) {
            List<Map<String, Object>> inlineRows = rowsFromValue(template.get("rows"));
            List<Map<String, Object>> sampleRows = inlineRows.isEmpty()
                    ? rowsFromValue(datasource.get("sampleRows"))
                    : inlineRows;
            int maxRows = task.get("max_rows") instanceof Number number
                    ? Math.max(1, Math.min(number.intValue(), MAX_EXTRACTION_ROWS))
                    : 10_000;
            return Map.of("rows", sampleRows.stream().limit(maxRows).toList(), "source", "test-fixture");
        }
        String jdbcUrl = text(datasource, "jdbcUrl", null);
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return Map.of("rows", queryJdbc(task, template, datasource, parameters), "source", jdbcUrl);
        }
        throw new IllegalArgumentException("Datasource requires jdbcUrl; inline rows are only allowed for TEST_FIXTURE");
    }

    private List<Map<String, Object>> queryJdbc(Map<String, Object> task, Map<String, Object> template,
                                                Map<String, Object> datasource,
                                                Map<String, Object> parameters) throws Exception {
        String customSql = text(template, "sql", null);
        if (customSql != null && !customSql.isBlank()) {
            throw new IllegalArgumentException("Custom extraction SQL is not allowed; use rootTable, columns and parameterized where");
        }
        String sql = buildSelectSql(String.valueOf(task.get("root_table")), template);
        NamedSql namedSql = bindNamedParameters(sql, parameters);
        int requestedRows = task.get("max_rows") instanceof Number number ? number.intValue() : 10_000;
        int requestedTimeout = task.get("timeout_seconds") instanceof Number number ? number.intValue() : 5;
        int maxRows = Math.max(1, Math.min(requestedRows, MAX_EXTRACTION_ROWS));
        int timeoutSeconds = Math.max(1, Math.min(requestedTimeout, MAX_TIMEOUT_SECONDS));
        try (Connection connection = DriverManager.getConnection(
                text(datasource, "jdbcUrl", null),
                text(datasource, "username", ""),
                text(datasource, "password", ""))) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (PreparedStatement statement = connection.prepareStatement(namedSql.sql())) {
                statement.setMaxRows(maxRows);
                statement.setQueryTimeout(timeoutSeconds);
                for (int i = 0; i < namedSql.values().size(); i++) {
                    statement.setObject(i + 1, namedSql.values().get(i));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Map<String, Object>> rows = rows(resultSet);
                    connection.rollback();
                    return rows;
                }
            }
        }
    }

    private String buildSelectSql(String rootTable, Map<String, Object> template) {
        requireIdentifier(rootTable, "rootTable");
        String columns = "*";
        Object columnValue = template.get("columns");
        if (columnValue instanceof List<?> list && !list.isEmpty()) {
            List<String> validatedColumns = list.stream().map(String::valueOf).toList();
            validatedColumns.forEach(column -> requireIdentifier(column, "column"));
            columns = String.join(", ", validatedColumns);
        }
        String where = text(template, "where", null);
        if (where != null && !where.isBlank()) {
            requireSafeWhere(where);
        }
        return "select " + columns + " from " + rootTable
                + (where == null || where.isBlank() ? "" : " where " + where);
    }

    private NamedSql bindNamedParameters(String sql, Map<String, Object> parameters) {
        Matcher matcher = NAMED_PARAMETER.matcher(sql);
        StringBuilder rewritten = new StringBuilder();
        List<Object> values = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group().substring(1);
            if (!parameters.containsKey(name)) {
                throw new IllegalArgumentException("Missing extraction parameter: " + name);
            }
            matcher.appendReplacement(rewritten, "?");
            values.add(parameters.get(name));
        }
        matcher.appendTail(rewritten);
        return new NamedSql(rewritten.toString(), values);
    }

    private List<Map<String, Object>> rows(ResultSet resultSet) throws Exception {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                row.put(metadata.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private void failTask(RequestContext context, Map<String, Object> task, Exception e) {
        String taskId = String.valueOf(task.get("id"));
        long queuedVersion = ((Number) task.get("version")).longValue();
        long runningVersion = queuedVersion + 1;
        long failedVersion = runningVersion + 1;
        int updatedCount = jdbcTemplate.update("""
                update extraction_task
                   set status = 'FAILED', version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, failedVersion, context.actor(), timestamp(clock.instant()), taskId, runningVersion);
        if (updatedCount == 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into extraction_execution(
                    id, extraction_task_id, worker_id, status, started_at, finished_at, metrics_json, error_message
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, "extraction-execution-" + UUID.randomUUID(), taskId, context.actor(), "FAILED",
                timestamp(clock.instant()), timestamp(clock.instant()), PlatformJson.write(Map.of()),
                e.getClass().getName() + ": " + e.getMessage());
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from extraction_task where id = ?", taskId));
        eventWriter.recordEvent(context, "extraction_task.worker_failed", "extraction_task", taskId,
                failedVersion, task, updated, "FAILED", "extraction worker failed",
                Map.of("error", e.getClass().getName(), "message", String.valueOf(e.getMessage())));
    }

    private void requireIdentifier(String value, String field) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid extraction " + field + ": " + value);
        }
    }

    private void requireSafeWhere(String where) {
        if (!SAFE_WHERE.matcher(where).matches() || FORBIDDEN_SQL.matcher(where).find()) {
            throw new IllegalArgumentException("Unsafe extraction where clause");
        }
    }

    private List<Map<String, Object>> rowsFromValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(PlatformJson.stringKeyMap(map));
            }
        }
        return rows;
    }

    private String text(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private RequestContext systemContext() {
        return new RequestContext("extraction-worker", "extraction-" + clock.instant().toEpochMilli(),
                "127.0.0.1", "scheduler");
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record NamedSql(String sql, List<Object> values) {
    }
}
