# Changelog

## 1.1.0 - 2026-09-27

First published release. It includes everything from the unreleased 1.0.0 milestone.

### Backups and restores

- MySQL, MariaDB, PostgreSQL, MongoDB and SQLite, with a connection test before every operation
  (`dbbackup test-connection`).
- Full logical backups, optionally restricted to some tables or collections, or to the schema or data
  only.
- Incremental and differential backups using each engine's native mechanism: MySQL/MariaDB binary
  logs, the MongoDB oplog (replica sets), PostgreSQL 17 incremental base backups (restored with
  `pg_combinebackup` into `--target-dir`) and SQLite page changes. Restores apply the whole chain.
- Restore by id or `latest`, from any storage target or from a local file, into the original or
  another database, optionally only some tables or collections.
- Works without a configuration file for one-off backups (`--db-type`, `--db-host`, ... `--output-dir`).

### Compression, encryption and storage

- gzip (default), bzip2, xz or no compression. A SHA-256 checksum is recorded for every backup and
  verified before restoring.
- Encryption with [age](https://age-encryption.org), to public keys or with a passphrase
  (`dbbackup keygen` creates a key pair). Encrypted backups can also be decrypted with the `age` tool.
- Local directories, Amazon S3 and S3-compatible services, Google Cloud Storage and Azure Blob Storage.
  A backup can be stored in several places at once (`dbbackup test-storage` checks access).

### Operations

- Retention by count and/or age, per storage target. Incremental backups are kept or deleted together
  with the full backup they depend on.
- Scheduling with cron expressions: a built-in scheduler daemon or generated crontab entries. Schedules
  can take full, incremental or differential backups.
- Console output, a rotating log file and a JSON-lines activity history (`dbbackup history`).
- Slack and webhook notifications on failure or on every operation.

### Requirements

- Java 21 or newer, and the client tools of the databases you back up (see the README).
