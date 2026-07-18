import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const api = read('../../openapi/v1.yaml');
const service = read('../../services/sync/Program.cs');
const compose = read('../../deploy/compose.yaml');
const backup = read('../../deploy/backup/backup.sh');
for (const path of ['/auth/register', '/auth/verify-email', '/auth/login', '/auth/refresh',
  '/sync/push', '/sync/pull', '/conflicts', '/media/prepare', '/devices', '/account']) {
  assert(api.includes(path), `OpenAPI is missing ${path}`);
}
for (const token of ['AddIdentityCore', 'RequireConfirmedEmail', 'AddMinutes(15)',
  'AddDays(30)', 'TokenHash', 'IsolationLevel.Serializable', 'ProtectForWorkspace',
  'device_counter_reuse', 'device_counter_regression', 'sameDevicePredecessor',
  'operation_id_reuse', 'x.ExpiresAt > now',
  'stored.Device.RevokedAt is not null', 'FixedTokenEquals', '/account/{kind}',
  'location.hash.slice(1)', 'projectedFields[field.Name] = accepted.Clone()']) {
  assert(service.includes(token), `sync service is missing ${token}`);
}
for (const token of ['postgres:18', 'object-store', 'caddy', 'MASTER_KEY_BASE64', 'METRICS_TOKEN']) {
  assert(compose.includes(token), `deployment is missing ${token}`);
}
assert(compose.includes('object-data:/object-data:ro') &&
  backup.includes('restic backup "$dump" /object-data'),
  'offsite backup must include both PostgreSQL and immutable media objects');
assert(compose.includes('http://localhost:9000/minio/health/live') &&
  !compose.includes('mc, ready, local'),
  'MinIO healthcheck must not rely on an unconfigured mc alias');
assert(!service.includes('Console.WriteLine(request)'), 'request content must not be logged');
assert(!service.includes('?userId={token.UserId}&token='),
  'email verification/reset tokens must not be placed in logged URL query strings');
console.log('Backend API, security, and deployment source contracts passed');
