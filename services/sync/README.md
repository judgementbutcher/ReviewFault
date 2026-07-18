# ReviewFault sync service

The service is the `/api/v1` implementation defined by `openapi/v1.yaml`. It
requires .NET 10, PostgreSQL 18, a 32-byte base64 master key and a JWT signing
secret. Content fields are encrypted with a random workspace data key; workspace
keys are wrapped by the deployment master key. Refresh tokens are random,
device-scoped, rotated on every use and stored only as SHA-256 hashes.
The `/metrics` endpoint requires `Authorization: Bearer $METRICS_TOKEN`; health
and readiness probes remain separate and expose no account or content data.

Create an invitation without putting its value in shell history:

```sh
docker compose -f deploy/compose.yaml run --rm sync --create-invite 7
```

The command prints the invite once and stores only its SHA-256 hash. Production
deployments must route verification/reset audit events into a reliable SMTP
queue before registration is enabled. The Compose backup service snapshots both
the PostgreSQL dump and the read-only object-store volume to the configured
offsite restic repository. Logs must retain metadata only; request bodies and
authentication headers must never be enabled in proxy or application logging.
The deployment master key must be escrowed separately in the operator's secret
manager; losing it makes encrypted workspace payloads and mail tokens unrecoverable.
