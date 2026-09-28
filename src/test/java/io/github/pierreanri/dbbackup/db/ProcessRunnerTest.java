package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {

    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesStdoutAndPassesEnvironment() {
        ProcessResult result = runner.run(ProcessSpec.builder(List.of("sh", "-c", "echo \"hello $GREETING\""))
                .env("GREETING", "world").build());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello world\n");
    }

    @Test
    void redirectsStdinAndStdoutToFiles() throws IOException {
        Path in = Files.writeString(tmp.resolve("in.txt"), "b\na\n");
        Path out = tmp.resolve("out.txt");
        runner.run(ProcessSpec.builder(List.of("sort")).stdin(in).stdout(out).build());
        assertThat(Files.readString(out)).isEqualTo("a\nb\n");
    }

    @Test
    void failsWithRedactedStderr() {
        ProcessSpec spec = ProcessSpec.builder(List.of("sh", "-c", "echo 'bad password s3cret!' >&2; exit 3"))
                .secret("s3cret!").build();
        assertThatThrownBy(() -> runner.run(spec))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("'sh' failed with exit code 3")
                .hasMessageContaining("bad password ****")
                .hasMessageNotContaining("s3cret!");
    }

    @Test
    void reportsMissingPrograms() {
        ProcessSpec spec = ProcessSpec.builder(List.of("definitely-not-a-real-dump-tool"))
                .missingHint("Install the client tools.").build();
        assertThatThrownBy(() -> runner.run(spec))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Cannot run 'definitely-not-a-real-dump-tool'")
                .hasMessageContaining("Install the client tools.");
    }

    @Test
    void enforcesTimeouts() {
        ProcessSpec spec = ProcessSpec.builder(List.of("sleep", "30")).timeout(Duration.ofMillis(200)).build();
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(spec))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void resolvesExecutablesFromBinPath() throws IOException {
        Path tool = Files.createFile(tmp.resolve("pg_dump"));
        assertThat(tool.toFile().setExecutable(true)).isTrue();

        assertThat(Executables.resolve(tmp.toString(), "missing", "pg_dump")).isEqualTo(tool.toString());
        assertThat(Executables.resolve(tmp.toString(), "nothing")).isEqualTo(tmp.resolve("nothing").toString());
        assertThat(Executables.resolve(null, "sh")).isEqualTo("sh");
        assertThat(Executables.resolve(null, "not-on-path-xyz", "sh")).isEqualTo("sh");
    }

    @Test
    void secretFilesArePrivate() throws IOException {
        Path file = SecretFiles.create("test", ".cnf", "password=x");
        try {
            assertThat(Files.readString(file)).isEqualTo("password=x");
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
        } finally {
            SecretFiles.deleteQuietly(file);
        }
        assertThat(file).doesNotExist();
    }
}
