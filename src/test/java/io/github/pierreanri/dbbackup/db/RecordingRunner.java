package io.github.pierreanri.dbbackup.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double of {@link ProcessRunner} recording the commands and the content of the temporary
 * credential files they reference (those files are deleted right after the command).
 */
class RecordingRunner extends ProcessRunner {

    final List<ProcessSpec> specs = new ArrayList<>();
    final Map<String, String> credentialFiles = new LinkedHashMap<>();
    final Deque<String> outputs = new ArrayDeque<>();

    RecordingRunner respond(String stdout) {
        outputs.add(stdout);
        return this;
    }

    @Override
    public ProcessResult run(ProcessSpec spec) {
        specs.add(spec);
        for (String arg : spec.command()) {
            for (String prefix : List.of("--defaults-extra-file=", "--config=")) {
                if (arg.startsWith(prefix)) {
                    try {
                        credentialFiles.put(prefix, Files.readString(Path.of(arg.substring(prefix.length()))));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return new ProcessResult(0, outputs.isEmpty() ? "" : outputs.poll(), "");
    }

    ProcessSpec last() {
        return specs.get(specs.size() - 1);
    }

    List<String> lastCommand() {
        return last().command();
    }
}
