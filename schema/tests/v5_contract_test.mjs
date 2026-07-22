import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const backup = JSON.parse(read('../backup-v5.schema.json'));
assert.equal(backup.properties.version.const, 5);
assert.equal(backup.properties.schedulerAbiVersion.const, 5);
const migration = read('../migrations/005_v0_5.sql');
for (const token of ['learning_profile_v5', 'learning_unit_v5', 'learning_task_v5',
  'learning_unit_relation_v5', 'learning_evidence_v5', 'learning_personalization_v5',
  'card_profile_v5', 'scale_mapping', 'formula_rule', 'math_error',
  'sync_card_profile_v5_update', 'awaiting_variant', 'sync_learning_evidence_v5_insert'])
  assert(migration.includes(token), `v5 migration is missing ${token}`);
assert(JSON.parse(read('../sync-operation-v1.schema.json')).properties.entityType.enum.includes('cardProfile'));
for (const path of [
  '../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt',
  '../../apps/windows/ReviewFault/Data/AppRepository.cs',
  '../../apps/harmony/entry/src/main/ets/data/LocalDatabase.ets',
]) {
  const source = read(path);
  for (const token of ['005_v0_5.sql', 'learningEvidence', 'cardProfile', 'card_profile_v5',
    'knowledge_point', 'structured_payload_json'])
    assert(source.includes(token), `${path} does not project v5 learning evidence`);
}
for (const path of [
  '../../apps/android/app/src/main/java/cn/reviewfault/app/MainActivity.kt',
  '../../apps/windows/ReviewFault/MainWindow.xaml.cs',
  '../../apps/harmony/entry/src/main/ets/pages/Index.ets',
]) {
  const source = read(path);
  for (const token of ['考点', '评分要点', '分层提示', '错因', '迁移', '作答前信心'])
    assert(source.includes(token), `${path} is missing scientific card UI field ${token}`);
}
for (const path of [
  '../../apps/android/app/src/main/cpp/CMakeLists.txt',
  '../../apps/harmony/entry/src/main/cpp/CMakeLists.txt',
]) assert(read(path).includes('scheduler_v5.cpp'), `${path} does not compile the v5 core`);
assert(read('../../services/sync/Program.cs').includes('learningEvidence'),
  'sync service does not accept immutable v5 evidence');
console.log('v5 learning-unit, evidence, and client source contracts passed');
