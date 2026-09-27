#!/bin/sh
# docker-entrypoint-initdb.d script: allow replication connections (pg_basebackup) with a password.
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
