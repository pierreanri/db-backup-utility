package io.github.pierreanri.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

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
    void everyCommandHasHelp() {
        List<CommandLine> commands = new ArrayList<>();
        collect(RootCommand.newCommandLine(), commands);
        assertThat(commands).hasSizeGreaterThan(12);
        for (CommandLine command : commands) {
            if (command.getCommandName().equals("help")) {
                continue;
            }
            List<String> args = new ArrayList<>();
            for (CommandLine c = command; c.getParent() != null; c = c.getParent()) {
                args.add(0, c.getCommandName());
            }
            args.add("--help");
            StringWriter out = new StringWriter();
            CommandLine cmd = RootCommand.newCommandLine();
            cmd.setOut(new PrintWriter(out));
            assertThat(cmd.execute(args.toArray(String[]::new))).as(String.join(" ", args)).isZero();
            assertThat(out.toString()).contains("Usage: dbbackup");
        }
    }

    private static void collect(CommandLine command, List<CommandLine> into) {
        into.add(command);
        command.getSubcommands().values().stream().distinct().forEach(sub -> collect(sub, into));
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
