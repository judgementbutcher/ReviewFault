import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const readJson = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const operation = readJson('../sync-operation-v1.schema.json');
const ink = readJson('../reviewfault-ink-v1.schema.json');
const backup = readJson('../backup-v4.schema.json');

assert.deepEqual(operation.required, [
  'operationId', 'deviceId', 'deviceCounter', 'baseCursor', 'baseRevision',
  'entityType', 'entityId', 'action', 'changedFields', 'occurredAt',
]);
assert(operation.properties.action.enum.includes('restore'));
assert.deepEqual(ink.properties.format, { const: 'reviewfault-ink' });
assert.equal(ink.properties.pages.items.properties.strokes.items.properties.points
  .items.properties.pressure.maximum, 1);
assert.deepEqual(backup.properties.excludedTables.const, [
  'local_device', 'sync_cursor', 'sync_revision', 'sync_outbox',
  'sync_conflict', 'local_ink_draft',
]);

for (const repositoryPath of [
  '../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt',
  '../../apps/windows/ReviewFault/Data/AppRepository.cs',
]) {
  const source = readFileSync(new URL(repositoryPath, import.meta.url), 'utf8');
  for (const token of ['review_action_v4', 'sync_outbox', 'local_device',
    'sync_conflict', 'local_ink_draft', 'attemptArtifact', '004_v0_4.sql']) {
    assert(source.includes(token), `${repositoryPath} does not integrate ${token}`);
  }
}
const windows = readFileSync(new URL(
  '../../apps/windows/ReviewFault/Data/AppRepository.cs', import.meta.url), 'utf8');
assert(windows.includes('ApplyRemoteAttemptArtifactAsync'),
  'Windows must project synced formal attempt artifacts');
assert(windows.includes('json_each(remove_op.observed_adds_json)'),
  'Windows must materialize observed-remove relations from active add facts');
assert(!windows.includes('Environment.MachineName'), 'Windows must not upload the machine name');
for (const bindingPath of [
  '../../apps/android/app/src/main/cpp/scheduler_jni.cpp',
  '../../apps/windows/ReviewFault/Core/NativeScheduler.cs',
  '../../apps/harmony/entry/src/main/cpp/napi_init.cpp',
]) {
  const binding = readFileSync(new URL(bindingPath, import.meta.url), 'utf8');
  assert(binding.includes('canonical') && (binding.includes('v4') || binding.includes('V4')),
    `${bindingPath} does not expose v4 canonical replay`);
}
const harmonyDatabase = readFileSync(new URL(
  '../../apps/harmony/entry/src/main/ets/data/LocalDatabase.ets', import.meta.url), 'utf8');
for (const invalidSql of ['s.scheduler_state', 'schedule_state_v2 SET scheduler_state',
  'LEFT JOIN memory_schedule_state ms']) {
  assert(!harmonyDatabase.includes(invalidSql),
    `Harmony database still references invalid v4 SQL: ${invalidSql}`);
}
for (const requiredProjection of ['UPDATE study_item SET scheduler_state',
  'LEFT JOIN math_schedule_state ms', 'remoteApplyCounterStart',
  'json_each(remove_op.observed_adds_json)']) {
  assert(harmonyDatabase.includes(requiredProjection),
  `Harmony database is missing v4 projection behavior: ${requiredProjection}`);
}
assert(!harmonyDatabase.includes("errorReason: ''"),
  'Harmony review facts must use null/absent error reasons accepted by schema v4');
const androidSync = readFileSync(new URL(
  '../../apps/android/app/src/main/java/cn/reviewfault/app/sync/SyncClient.kt', import.meta.url), 'utf8');
const harmonySync = readFileSync(new URL(
  '../../apps/harmony/entry/src/main/ets/sync/SyncClient.ets', import.meta.url), 'utf8');
assert(androidSync.includes('URI(value)') && harmonySync.includes('localhost|127\\.0\\.0\\.1'),
  'mobile sync endpoints must parse or exactly match local development hosts');
assert(!readFileSync(new URL('../../apps/android/app/src/main/AndroidManifest.xml', import.meta.url), 'utf8')
  .includes('LegacyMainActivity'), 'Android legacy activity must be removed');
console.log('v4 wire, ink, backup, and client source contracts passed');
