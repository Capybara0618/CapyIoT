package io.capyiot.service.runtime;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Dedicated Job entry point. Does not start Spring, HTTP, consumers, schedulers or signers. */
public final class SchemaMigrationMain {
    public static void main(String[] args) throws Exception {
        String domain = System.getenv("CAPYIOT_SCHEMA");
        if (!Set.of("device", "alarm", "workflow", "platform").contains(domain))
            throw new IllegalArgumentException("Explicit owned schema required");
        String action = System.getenv().getOrDefault("CAPYIOT_MIGRATION_ACTION", "validate");
        if (!Set.of("migrate", "validate").contains(action)) throw new IllegalArgumentException("Invalid migration action");
        String url = "jdbc:mysql://mysql:3306/" + domain + "_db?connectionTimeZone=UTC";
        String password = Files.readString(Path.of("/run/secrets/db-password"));
        String user = domain + "_app";
        try (var connection = DriverManager.getConnection(url, user, password);
             var claim = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            claim.setString(1, "capyiot:" + domain + ":migration");
            try (var result = claim.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Migration already running");
            }
            try {
                var flyway = Flyway.configure().dataSource(url,user,password).cleanDisabled(true)
                    .ignoreMigrationPatterns(new String[0])
                    .locations(domain.equals("platform") ? "classpath:db/platform" : "classpath:db/migration")
                    .validateMigrationNaming(true).load();
                if (action.equals("migrate")) flyway.migrate(); else flyway.validate();
            } finally {
                try (var release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, "capyiot:" + domain + ":migration"); release.execute();
                }
            }
        }
    }
}
