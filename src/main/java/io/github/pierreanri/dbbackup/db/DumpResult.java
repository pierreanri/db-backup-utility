package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a dump produced besides the dump file itself.
 *
 * @param checkpoint engine specific position the next incremental backup starts from (binary log
 *                   position, oplog timestamp...); empty when the backup cannot start a chain
 * @param stateFile  optional file the next incremental backup needs (e.g. page fingerprints),
 *                   stored unencrypted next to the backup
 * @param method     {@code logical} (default) or {@code physical}
 */
public record DumpResult(Map<String, String> checkpoint, Path stateFile, String method) {

    public static final String LOGICAL = "logical";
    public static final String PHYSICAL = "physical";
    public static final DumpResult NONE = new DumpResult(Map.of(), null, LOGICAL);

    public DumpResult {
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        method = method == null ? LOGICAL : method;
    }

    public static DumpResult of(Map<String, String> checkpoint) {
        return new DumpResult(checkpoint, null, LOGICAL);
    }
}
