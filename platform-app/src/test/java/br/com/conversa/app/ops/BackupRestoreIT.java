package br.com.conversa.app.ops;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.business.BusinessDataImporter;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M9 acceptance: a backup written by {@code deploy/backup/backup.sh} is restored by
 * {@code restore.sh} into a new database with the same data and schema version.
 */
@SpringBootTest(properties = "conversa.workers.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BackupRestoreIT {

    private static final Path SCRIPTS = Path.of("..", "deploy", "backup");

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    BusinessDataImporter importer;

    @Test
    void aBackupIsRestoredIntoANewDatabase() throws Exception {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant").update();
        TenantId tenant = tenants.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        importer.importMenu(tenant, "code;category;name;price\nP01;Pizzas;Pizza Calabresa;49,90\nB01;Bebidas;Refri;6,50\n", null);

        for (String script : new String[] {"backup.sh", "restore.sh"}) {
            postgres.copyFileToContainer(MountableFile.forHostPath(SCRIPTS.resolve(script)), "/scripts/" + script);
        }
        String[] env = {"env", "PGHOST=localhost", "PGUSER=" + postgres.getUsername(),
                "PGPASSWORD=" + postgres.getPassword(), "PGDATABASE=" + postgres.getDatabaseName(), "BACKUP_DIR=/tmp/backups"};

        Container.ExecResult backup = postgres.execInContainer(concat(env, "sh", "/scripts/backup.sh"));
        assertThat(backup.getExitCode()).as(backup.getStderr()).isZero();
        assertThat(backup.getStdout()).contains("backup written: /tmp/backups/conversa-");
        String file = postgres.execInContainer("sh", "-c", "ls /tmp/backups/conversa-*.dump").getStdout().strip();

        Container.ExecResult restore = postgres.execInContainer(concat(env, "sh", "/scripts/restore.sh", file, "conversa_restore"));
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
        assertThat(restore.getStdout()).contains("restored 1 tenants, 0 orders");

        DriverManagerDataSource restored = new DriverManagerDataSource(
                postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/conversa_restore"),
                postgres.getUsername(), postgres.getPassword());
        JdbcClient copy = JdbcClient.create(restored);
        assertThat(copy.sql("SELECT name || ' ' || price FROM catalog_item ORDER BY position").query(String.class).list())
                .containsExactly("Pizza Calabresa 49.90", "Refri 6.50");
        assertThat(copy.sql("SELECT max(version::int) FROM flyway_schema_history").query(Integer.class).single())
                .isEqualTo(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history").query(Integer.class).single());
        assertThat(copy.sql("SELECT relrowsecurity FROM pg_class WHERE relname = 'catalog_item'").query(Boolean.class).single())
                .isTrue();
    }

    private static String[] concat(String[] first, String... rest) {
        String[] all = new String[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }
}
