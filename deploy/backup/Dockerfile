FROM postgres:18-alpine
RUN apk add --no-cache restic
COPY deploy/backup/backup.sh /usr/local/bin/reviewfault-backup
RUN chmod 0755 /usr/local/bin/reviewfault-backup
ENTRYPOINT ["/usr/local/bin/reviewfault-backup"]
