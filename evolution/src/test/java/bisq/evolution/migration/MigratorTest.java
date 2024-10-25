package bisq.evolution.migration;

import bisq.common.application.ApplicationVersion;
import bisq.common.platform.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class MigratorTest {
    @Test
    void migrationSuccess(@TempDir Path dataDir) throws IOException {
        Path versionFilePath = dataDir.resolve("version");
        Version dataDirVersion = new Version("2.1.0");
        Files.writeString(versionFilePath, dataDirVersion.toString());

        Version appVersion = ApplicationVersion.getVersion();
        Migrator migrator = new Migrator(appVersion, dataDir);

        migrator.migrate();

        String readVersion = Files.readString(dataDir.resolve("version"));
        assertThat(readVersion).isEqualTo(appVersion.toString());
    }
}
