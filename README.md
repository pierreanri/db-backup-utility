# db-backup-utility

[![CI](https://github.com/pierreanri/db-backup-utility/actions/workflows/ci.yml/badge.svg)](https://github.com/pierreanri/db-backup-utility/actions/workflows/ci.yml)

`dbbackup` is a command-line utility to back up and restore **MySQL/MariaDB**, **PostgreSQL**,
**MongoDB** and **SQLite** databases. Backups are compressed, checksummed and stored locally or in the
cloud (**Amazon S3** and S3-compatible services, **Google Cloud Storage**, **Azure Blob Storage**). The
utility can run backups on a schedule, apply retention rules, log everything it does and notify you
when something goes wrong.

```text
$ dbbackup backup shop
Backup shop-20260927T020000Z completed in 2.1 s
  database:  shop (postgresql shop)
  size:      8.8 MiB (raw 61.5 MiB, gzip)
  sha256:    44e93859b24478ffdab79392f1fd0632d17be3920852c3a1bdcee4cf492581ef
  stored:    /var/backups/dbbackup/shop/shop-20260927T020000Z.dump.gz
             s3://my-company-backups/prod/shop/shop-20260927T020000Z.dump.gz
  pruned:    shop-20260920T020000Z (s3)
```

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Commands](#commands)
- [Configuration](#configuration)
- [Storage targets](#storage-targets)
- [Scheduling](#scheduling)
- [Logging and history](#logging-and-history)
- [Notifications](#notifications)
- [How backups are stored](#how-backups-are-stored)
- [Security](#security)
- [Development](#development)
- [Limitations and ideas](#limitations-and-ideas)

## Features

- **Databases**: MySQL, MariaDB, PostgreSQL, MongoDB and SQLite, through a common adapter interface.
- **Connection testing** before every operation, and on demand with `dbbackup test-connection`.
- **Full logical backups**, optionally restricted to some tables/collections or to the schema/data only.
- **Compression**: gzip (default), bzip2, xz or none. A SHA-256 checksum is recorded for every backup
  and verified before restoring.
- **Storage**: local directory, Amazon S3 and S3-compatible services (MinIO, Ceph, Wasabi, Cloudflare R2,
  Backblaze B2...), Google Cloud Storage, Azure Blob Storage. A backup can go to several targets at once.
- **Restore** by id or `latest`, from any target or from a local file, into the original or another
  database, optionally only some tables/collections.
- **Retention**: keep the N most recent backups and/or delete backups older than N days, per target.
- **Scheduling** with cron expressions: a built-in scheduler daemon or generated crontab entries.
- **Logging**: console output, a rotating log file and a JSON-lines activity history
  (`dbbackup history`).
- **Notifications** to Slack and/or any HTTP webhook, on failure (default) or on every operation.
- **Works without a config file** for one-off backups (`--db-type`, `--db-host`, ... `--output-dir`).

## Requirements

- **Java 21** or newer.
- The native client tools of the databases you back up, available on the `PATH` or in the directory
  given by `binPath`:

| Database        | Tools used                                         | Package examples                                          |
|-----------------|----------------------------------------------------|-----------------------------------------------------------|
| PostgreSQL      | `pg_dump`, `pg_restore`, `psql`                    | `postgresql-client` (use a version ≥ the server's)        |
| MySQL / MariaDB | `mysqldump`, `mysql` (or `mariadb-dump`, `mariadb`) | `mysql-client`, `mariadb-client`                          |
| MongoDB         | `mongodump`, `mongorestore`                        | [MongoDB Database Tools](https://www.mongodb.com/try/download/database-tools) |
| SQLite          | none (built in)                                    |                                                           |

## Installation

Build the self-contained executable jar with Maven:

```bash
git clone https://github.com/pierreanri/db-backup-utility.git
cd db-backup-utility
mvn package                  # add -DskipTests to skip the tests
bin/dbbackup --version       # or: java -jar target/dbbackup.jar --version
```

Every CI run on `main` also publishes the jar as the `dbbackup-jar` artifact.

To install it for your user:

```bash
mkdir -p ~/.local/lib/dbbackup ~/.local/bin
cp target/dbbackup.jar ~/.local/lib/dbbackup/
cp bin/dbbackup ~/.local/lib/dbbackup/
ln -s ~/.local/lib/dbbackup/dbbackup ~/.local/bin/dbbackup
```

The `bin/dbbackup` launcher looks for `dbbackup.jar` next to itself, in `../lib` or in `../target`
(or in `$DBBACKUP_JAR`), uses `$JAVA_HOME` when set and passes `$DBBACKUP_JAVA_OPTS` to the JVM.

## Quick start

```bash
# 1. Create an annotated configuration file (~/.dbbackup/config.yml) and edit it
dbbackup config init
dbbackup config validate

# 2. Check that the databases and storage targets are reachable
dbbackup test-connection
dbbackup test-storage

# 3. Back up, list, restore
dbbackup backup app-postgres
dbbackup list
dbbackup restore latest --db app-postgres --target-database app_restored

# 4. Automate
dbbackup schedule list
dbbackup schedule daemon          # or: dbbackup schedule cron --install
```

One-off backup without a configuration file:

```bash
export PGPASS=secret
dbbackup backup --db-type postgresql --db-host db.internal --db-user backup \
  --db-password-env PGPASS --db-name shop --output-dir ./backups
dbbackup backup --db-type sqlite --db-file ./app.db --output-dir ./backups --compression xz
```

## Commands

Run `dbbackup COMMAND --help` for every option. Global options, usable before or after the command:
`-c/--config FILE`, `-v/--verbose`, `-q/--quiet`, `--log-dir DIR`.

| Command | Description |
|---------|-------------|
| `test-connection [DATABASE...]` | Connect to the databases (all configured ones by default) and print the server version. |
| `test-storage [-s NAME]` | Write, read back and delete a probe object on each storage target. |
| `backup DATABASE... \| --all` | Back up databases. See the options below. |
| `restore BACKUP_ID \| latest \| --file FILE` | Restore a backup. See the options below. |
| `list [DATABASE]` | List the backups of all targets (or `-s NAME`), newest first. `--json` for scripts. |
| `prune [DATABASE...]` | Delete expired backups according to the retention rules. `--dry-run` shows what would go. |
| `history` | Show the activity history. `-n 50`, `--database app`, `--failed`, `--json`. |
| `schedule list \| daemon \| run NAME \| cron` | Scheduling, see [Scheduling](#scheduling). |
| `config init [FILE] \| validate` | Write the example configuration, or check a configuration file. |

### `backup`

```bash
dbbackup backup app                                    # one profile, default storage and compression
dbbackup backup --all                                  # every configured database
dbbackup backup app shop --storage local,s3            # several databases and targets
dbbackup backup app --tables users,orders -C xz        # selected tables, xz compression
dbbackup backup app --schema-only                      # schema only (or --data-only)
dbbackup backup app --keep-last 3                      # retention override for this run
dbbackup backup app --no-retention --json              # keep everything, print the manifest as JSON
dbbackup backup app --db-host replica.internal         # override profile settings on the command line
```

The exit code is `0` when every backup succeeded and `1` otherwise, so it can be used in scripts.

### `restore`

```bash
dbbackup restore latest --db app                             # latest backup of 'app', into 'app'
dbbackup restore app-20260927T020000Z                        # a given backup, into its own profile
dbbackup restore latest --db app --target-database app_copy  # into another database (created if missing)
dbbackup restore latest --db app --tables users --clean      # only some tables, dropping them first
dbbackup restore latest --db app --storage s3                # read from a specific target
dbbackup restore --file ./app-20260927T020000Z.dump.gz --db app
```

Restores ask for confirmation; use `-y/--yes` in scripts. The backup's checksum is verified before
anything is changed (`--no-verify` skips this). Selective restore (`--tables`) is supported for
PostgreSQL, MongoDB and SQLite. MySQL dumps can be restricted at backup time with `--tables` instead.

## Configuration

The configuration is a YAML file looked up in this order: `--config FILE`, `$DBBACKUP_CONFIG`,
`./dbbackup.yml`, `~/.dbbackup/config.yml`. `dbbackup config init` writes a fully commented example,
also available in [`config/dbbackup.example.yml`](config/dbbackup.example.yml).

Any string can reference environment variables, which keeps secrets out of the file:
`${NAME}` or `${NAME:-default}` (`$${...}` produces a literal `${...}`). Unknown properties, invalid
values and dangling references are reported with their location when the file is loaded.

```yaml
defaults:
  compression: gzip            # gzip | bzip2 | xz | none
  storage: [local, s3]         # targets used when --storage is not given
  retention:
    keepLast: 7
    maxAgeDays: 30
  # workDir: /var/tmp/dbbackup # temporary dump files (default: system temp directory)
  # timeoutMinutes: 240        # abort dumps/restores taking longer

databases:
  app-postgres:
    type: postgresql           # mysql | mariadb | postgresql | mongodb | sqlite
    host: db.internal          # default: localhost
    port: 5432                 # default: the standard port of the type
    username: backup
    password: ${PGPASSWORD}
    database: app
    # binPath: /usr/lib/postgresql/16/bin
    # dumpArgs: ["--exclude-table=audit_log"]   # extra arguments for the dump tool
    # restoreArgs: ["--no-owner"]               # extra arguments for the restore tool
    # timeoutMinutes: 60
  shop-mysql:
    type: mysql
    host: 127.0.0.1
    username: backup
    password: ${MYSQL_BACKUP_PASSWORD}
    database: shop
  events-mongo:
    type: mongodb
    uri: mongodb+srv://backup:${MONGO_PASSWORD}@cluster0.example.net/
    database: events           # omit to dump every database of the server
    # or host/port/username/password/authDatabase instead of uri
  cache:
    type: sqlite
    file: /var/lib/myapp/cache.db

storage:
  local:
    type: local
    path: /var/backups/dbbackup
  s3:
    type: s3
    bucket: my-company-backups
    prefix: prod
    region: eu-west-1
    retention:
      keepLast: 30             # per-target override

schedules:
  - name: nightly
    database: app-postgres
    cron: "0 2 * * *"
    storage: [local, s3]

logging:
  dir: ~/.dbbackup/logs
  level: INFO

notifications:
  slack:
    webhookUrl: ${SLACK_WEBHOOK_URL}
```

Retention precedence: command line (`--keep-last`, `--max-age-days`) or schedule, then the storage
target's `retention`, then `defaults.retention`. The most recent backup of a database is never deleted.

## Storage targets

| Type | Settings | Authentication |
|------|----------|----------------|
| `local` | `path` | file system permissions |
| `s3` | `bucket`, `prefix`, `region`, `storageClass`, `endpoint` + `pathStyle` (S3-compatible services) | `accessKeyId`/`secretAccessKey` (+ `sessionToken`), `profile`, or the default AWS chain (env vars, `~/.aws`, instance/container roles) |
| `gcs` | `bucket`, `prefix`, `projectId`, `endpoint` (emulators) | `credentialsFile` (service account key) or Application Default Credentials |
| `azure` | `container`, `prefix`, `endpoint` (Azurite) | `connectionString`, or `accountName` with `accountKey` or `sasToken` |

Examples:

```yaml
storage:
  minio:
    type: s3
    bucket: backups
    endpoint: http://minio.internal:9000
    pathStyle: true
    accessKeyId: ${MINIO_ACCESS_KEY}
    secretAccessKey: ${MINIO_SECRET_KEY}
  gcs:
    type: gcs
    bucket: my-backups
    prefix: databases
    credentialsFile: ~/.config/gcloud/backup-sa.json
  azure:
    type: azure
    container: backups
    connectionString: ${AZURE_STORAGE_CONNECTION_STRING}
```

Large files are uploaded in parts (S3 multipart upload streamed from disk, GCS resumable upload,
Azure block upload). Buckets and containers must exist; `dbbackup test-storage` checks access.

## Scheduling

Schedules use standard 5-field cron expressions (`minute hour day-of-month month day-of-week`) or the
macros `@hourly`, `@daily`, `@weekly`, `@monthly` and `@yearly`. Each schedule can set its own
`storage`, `compression`, `scope` (`full`, `schema-only`, `data-only`), `tables`, `retention` and
`timezone`, and can be paused with `enabled: false`.

```bash
dbbackup schedule list                 # schedules and their next run
dbbackup schedule run nightly          # run one job now
```

There are two ways to run them:

**1. The built-in scheduler.** `dbbackup schedule daemon` runs in the foreground. A job never overlaps
with itself, a run missed while the machine was asleep is caught up once, and running backups are
allowed to finish on `SIGTERM` (`--shutdown-grace`). Example systemd unit:

```ini
# /etc/systemd/system/dbbackup.service
[Unit]
Description=dbbackup scheduler
After=network-online.target

[Service]
User=backup
EnvironmentFile=/etc/dbbackup/env           # PGPASSWORD=..., AWS_..., SLACK_WEBHOOK_URL=...
ExecStart=/usr/bin/java -jar /opt/dbbackup/dbbackup.jar --config /etc/dbbackup/config.yml schedule daemon
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**2. cron.** `dbbackup schedule cron` prints one crontab line per enabled schedule, each calling
`dbbackup schedule run NAME`; `--install` adds them to your crontab (in a managed block that is
updated on the next `--install`) and `--uninstall` removes them. cron starts jobs with a minimal
environment, so define the variables your configuration needs at the top of the crontab.

```text
# BEGIN dbbackup (managed by 'dbbackup schedule cron --install')
0 2 * * * '/usr/bin/java' -jar /opt/dbbackup/dbbackup.jar --config /etc/dbbackup/config.yml --quiet schedule run nightly >> /home/backup/.dbbackup/logs/cron.log 2>&1
# END dbbackup
```

## Logging and history

- The console shows progress (`-q` for warnings only, `-v` for debug output including the commands run).
- `~/.dbbackup/logs/dbbackup.log` (or `logging.dir`, `--log-dir`) keeps a detailed log, rotated daily
  and at `maxFileSize` (10 MB by default), compressed and kept `maxHistoryDays` (30) days.
- `~/.dbbackup/logs/history.jsonl` records every backup, restore and prune as one JSON line: time,
  operation, database, status (`SUCCESS`, `PARTIAL`, `FAILED`), backup id, size, duration, locations,
  trigger (`cli` or `schedule:NAME`), host and error message.

```text
$ dbbackup history -n 3
TIME                 OPERATION  DATABASE  STATUS   SIZE     DURATION  TRIGGER           DETAILS
2026-09-27 02:00:03  backup     shop      SUCCESS  8.8 MiB  2.1 s     schedule:nightly  shop-20260927T020000Z
2026-09-27 02:00:05  backup     blog      FAILED   -        102 ms    schedule:blog     'mysqldump' failed with exit code 2: Access denied...
2026-09-27 09:14:41  restore    shop      SUCCESS  8.8 MiB  1.4 s     cli               shop-20260927T020000Z
```

## Notifications

```yaml
notifications:
  slack:
    webhookUrl: ${SLACK_WEBHOOK_URL}   # Slack incoming webhook
    channel: "#ops"                     # optional
    onSuccess: false                    # default
    onFailure: true                     # default
  webhook:
    url: https://example.com/hooks/backups
    headers:
      Authorization: Bearer ${WEBHOOK_TOKEN}
    onSuccess: true
```

The webhook receives the history entry as JSON plus a `text` summary. Failing notifications are
logged and never make a backup fail.

## How backups are stored

Each backup is a single file plus a manifest, under `<target>/<prefix>/<database>/`:

```text
shop/shop-20260927T020000Z.dump.gz          # the (compressed) dump
shop/shop-20260927T020000Z.manifest.json    # written last: its presence marks a complete backup
```

| Database | Dump format | Extension |
|----------|-------------|-----------|
| PostgreSQL | `pg_dump` custom format (restorable with `pg_restore`) | `.dump` |
| MySQL / MariaDB | SQL script from `mysqldump --single-transaction` (routines, triggers and events included) | `.sql` |
| MongoDB | `mongodump --archive` | `.archive` |
| SQLite | SQLite database file from the online backup API (consistent while in use) | `.db` |

```json
{
  "formatVersion" : 1,
  "id" : "shop-20260927T020000Z",
  "database" : "shop",
  "databaseType" : "postgresql",
  "databaseName" : "shop",
  "host" : "db.internal",
  "scope" : "full",
  "tables" : [ ],
  "compression" : "gzip",
  "fileName" : "shop-20260927T020000Z.dump.gz",
  "sizeBytes" : 9214,
  "rawSizeBytes" : 64502,
  "sha256" : "44e93859b24478ffdab79392f1fd0632d17be3920852c3a1bdcee4cf492581ef",
  "createdAt" : "2026-09-27T02:00:00Z",
  "durationMillis" : 1874,
  "serverVersion" : "PostgreSQL 16.4",
  "toolVersion" : "1.0.0",
  "hostname" : "backup-01"
}
```

Backups are regular files: they can be restored without `dbbackup`, e.g.
`gunzip -c shop-...dump.gz | pg_restore -d shop`, `gunzip -c blog-...sql.gz | mysql blog` or
`gunzip -c events-...archive.gz | mongorestore --archive`.

## Security

- Passwords never appear on the command line of the dump tools: MySQL gets a private (`0600`)
  temporary option file, PostgreSQL the `PGPASSWORD` variable, MongoDB a private `--config` file.
  They are deleted right after use and masked in error messages and logs.
- Keep secrets in environment variables referenced as `${VAR}`; `config init` creates the file with
  `0600` permissions.
- Use a dedicated database user with read-only privileges for backups (plus the rights needed to
  restore, if the same profile is used for restores).
- Backups are not encrypted by dbbackup: rely on the storage's encryption at rest (S3 SSE, GCS and
  Azure default encryption) and restrict access to the backup location.

## Development

```bash
mvn verify                     # compile, unit tests, build target/dbbackup.jar
```

Project layout (`src/main/java/io/github/pierreanri/dbbackup/`):

| Package | Content |
|---------|---------|
| `cli` | picocli commands and console output |
| `config` | YAML configuration model, loader (env interpolation) and validation |
| `db` | database adapters (MySQL/MariaDB, PostgreSQL, MongoDB, SQLite) and the external process runner |
| `compression` | gzip/bzip2/xz streams and SHA-256 checksums |
| `storage` | local, S3, GCS and Azure backends |
| `core` | backup and restore pipelines, manifests, catalog, retention |
| `scheduling` | cron parsing, scheduler daemon, crontab generation |
| `logging` | Logback setup and the activity history |
| `notify` | Slack and webhook notifications |

### Integration tests

Integration tests run against real servers and storage emulators and are skipped unless their
environment variables are set. `docker-compose.yml` starts everything they need:

```bash
docker compose up -d
source dev/integration-env.sh
mvn verify
```

| Variables | Service |
|-----------|---------|
| `DBBACKUP_IT_PG_HOST`, `_PORT`, `_USER`, `_PASSWORD` | PostgreSQL |
| `DBBACKUP_IT_MYSQL_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_MARIADB` | MySQL or MariaDB |
| `DBBACKUP_IT_MONGO_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_BIN` | MongoDB |
| `DBBACKUP_IT_S3_ENDPOINT`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY` | S3 / MinIO / moto |
| `DBBACKUP_IT_GCS_ENDPOINT`, `_BUCKET`, `_PROJECT`, `_CREDENTIALS` | GCS / fake-gcs-server |
| `DBBACKUP_IT_AZURE_CONNECTION_STRING`, `DBBACKUP_IT_AZURE_CONTAINER` | Azure / Azurite |

The CI workflow runs the unit tests on JDK 21 and 25 and the integration tests against containers.

## Limitations and ideas

- Backups are full logical dumps. Incremental and differential backups (PostgreSQL WAL archiving or
  `pg_basebackup --incremental`, MySQL binary logs, MongoDB oplog) are not implemented yet.
- MySQL backups cannot be restored table by table (back up the tables separately instead) and
  `mongodump` backs up a single collection at a time when `--tables` is used.
- Client-side encryption of backups (e.g. age or AES-GCM) would be a useful addition.
- The target database of a restore is created when missing for MySQL and PostgreSQL; with MongoDB it
  is created implicitly.
