/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Console formatting helpers.
 */
final class Formats {

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Formats() {
    }

    static String time(Instant instant) {
        return instant == null ? "-" : LOCAL_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** Prints rows as left-aligned columns separated by two spaces. */
    static void table(PrintWriter out, List<String> header, List<List<String>> rows) {
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        int[] widths = new int[header.size()];
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i) == null ? 1 : row.get(i).length());
            }
        }
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i) == null ? "-" : row.get(i);
                line.append(cell);
                if (i < row.size() - 1) {
                    line.append(" ".repeat(widths[i] - cell.length() + 2));
                }
            }
            out.println(line.toString().stripTrailing());
        }
        out.flush();
    }
}
