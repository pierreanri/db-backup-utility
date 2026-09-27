package io.github.pierreanri.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class RootCommandTest {

    @Test
    void printsVersion() {
        StringWriter out = new StringWriter();
        CommandLine cmd = RootCommand.newCommandLine();
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute("--version");

        assertThat(exit).isZero();
        assertThat(out.toString()).startsWith("dbbackup ");
    }

    @Test
    void printsUsageWithoutArguments() {
        StringWriter out = new StringWriter();
        CommandLine cmd = RootCommand.newCommandLine();
        cmd.setOut(new PrintWriter(out));

        int exit = cmd.execute();

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("Usage: dbbackup");
    }
}
