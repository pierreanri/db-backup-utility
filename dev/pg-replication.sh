#!/bin/sh
# Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
# No license is granted to use, copy, modify or distribute this file without permission.
# docker-entrypoint-initdb.d script: allow replication connections (pg_basebackup) with a password.
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
