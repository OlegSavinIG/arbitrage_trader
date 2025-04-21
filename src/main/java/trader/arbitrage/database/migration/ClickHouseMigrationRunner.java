package trader.arbitrage.database.migration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClickHouseMigrationRunner {

    private final JdbcTemplate clickHouseJdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void runMigrations() throws SQLException {
        try {
            Thread.sleep(10000);
            createMigrationsTable();

            List<String> applied = getAppliedMigrations();
            List<Resource> resources = new ArrayList<>(Arrays.asList(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath:/clickhouse-migrations/*.sql")
            ));

            resources.sort(Comparator.comparing(r -> r.getFilename().toLowerCase()));

            for (Resource resource : resources) {
                String filename = Objects.requireNonNull(resource.getFilename());
                if (applied.contains(filename)) {
                    log.info("✅ Migration already applied: {}", filename);
                    continue;
                }

                log.info("📦 Applying migration: {}", filename);
                String sql = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                clickHouseJdbcTemplate.execute(sql);

                clickHouseJdbcTemplate.update("INSERT INTO clickhouse_migrations (filename, applied_at) VALUES (?, now())", filename);
                log.info("✅ Migration applied: {}", filename);
            }

        } catch (Exception e) {
            log.error("❌ Migration failed: ", e);
            throw new RuntimeException(e);
        }
    }

    private void createMigrationsTable() {
        clickHouseJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clickhouse_migrations (
              filename String,
              applied_at DateTime
            ) ENGINE = MergeTree()
            ORDER BY applied_at
        """);
    }

    private List<String> getAppliedMigrations() {
        try {
            return clickHouseJdbcTemplate.query("SELECT filename FROM clickhouse_migrations",
                    (rs, rowNum) -> rs.getString("filename"));
        } catch (Exception e) {
            return List.of();
        }
    }
}

