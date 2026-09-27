/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Common plumbing of the sub-commands.
 */
abstract class BaseCommand implements Callable<Integer> {

    static final int OK = 0;
    static final int FAILED = 1;

    @Spec
    CommandSpec spec;

    protected RootCommand root() {
        return (RootCommand) spec.root().userObject();
    }

    protected AppContext ctx() {
        return root().context();
    }

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }
}
