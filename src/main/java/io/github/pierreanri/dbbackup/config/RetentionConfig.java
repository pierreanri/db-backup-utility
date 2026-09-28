package io.github.pierreanri.dbbackup.config;

/**
 * Retention rules applied after each backup (and by {@code dbbackup prune}).
 *
 * @param keepLast   keep only the N most recent backups of a database in a storage target
 * @param maxAgeDays delete backups older than N days
 */
public record RetentionConfig(Integer keepLast, Integer maxAgeDays) {

    public static final RetentionConfig NONE = new RetentionConfig(null, null);

    public boolean isEmpty() {
        return keepLast == null && maxAgeDays == null;
    }

    /** Returns the first non-empty retention of the arguments, or {@link #NONE}. */
    public static RetentionConfig firstNonEmpty(RetentionConfig... candidates) {
        for (RetentionConfig candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return NONE;
    }

    public String describe() {
        if (isEmpty()) {
            return "keep everything";
        }
        StringBuilder sb = new StringBuilder();
        if (keepLast != null) {
            sb.append("keep last ").append(keepLast);
        }
        if (maxAgeDays != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("max age ").append(maxAgeDays).append(" days");
        }
        return sb.toString();
    }
}
