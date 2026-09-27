# Environment for the integration tests against the services of docker-compose.yml:
#   source dev/integration-env.sh && mvn verify
# The client tools (pg_dump, mysqldump, mysqlbinlog, mongodump...) must be installed locally;
# set DBBACKUP_IT_MONGO_BIN if the MongoDB Database Tools are not on the PATH.
export DBBACKUP_IT_PG_HOST=127.0.0.1 DBBACKUP_IT_PG_PORT=15432 DBBACKUP_IT_PG_USER=postgres DBBACKUP_IT_PG_PASSWORD=pg-secret
export DBBACKUP_IT_MYSQL_HOST=127.0.0.1 DBBACKUP_IT_MYSQL_PORT=13306 DBBACKUP_IT_MYSQL_USER=root DBBACKUP_IT_MYSQL_PASSWORD=mysql-secret
export DBBACKUP_IT_MONGO_HOST=127.0.0.1 DBBACKUP_IT_MONGO_PORT=17017 DBBACKUP_IT_MONGO_USER=root DBBACKUP_IT_MONGO_PASSWORD=mongo-secret
export DBBACKUP_IT_MONGO_RS_HOST=127.0.0.1 DBBACKUP_IT_MONGO_RS_PORT=27018
export DBBACKUP_IT_PG17_HOST=127.0.0.1 DBBACKUP_IT_PG17_PORT=15433 DBBACKUP_IT_PG17_USER=postgres DBBACKUP_IT_PG17_PASSWORD=pg-secret
export DBBACKUP_IT_PG17_BIN="${DBBACKUP_IT_PG17_BIN:-/usr/lib/postgresql/17/bin}"   # PostgreSQL 17 programs
export DBBACKUP_IT_S3_ENDPOINT=http://127.0.0.1:9000 DBBACKUP_IT_S3_ACCESS_KEY=minioadmin DBBACKUP_IT_S3_SECRET_KEY=minioadmin
export DBBACKUP_IT_GCS_ENDPOINT=http://127.0.0.1:4443
export DBBACKUP_IT_AZURE_CONNECTION_STRING='DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;'
