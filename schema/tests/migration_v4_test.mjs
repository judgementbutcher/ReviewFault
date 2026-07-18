import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const db = new DatabaseSync(':memory:');
db.exec(read('../migrations/001_initial.sql'));
db.exec(read('../migrations/002_v0_2.sql'));
db.exec(read('../migrations/003_v0_3.sql'));
db.exec(`
  INSERT INTO study_item (id, kind, subject, created_at, updated_at)
  VALUES ('memory', 'memory_card', 'operating_systems', 1, 1);
  INSERT INTO memory_card (study_item_id, template_type, prompt_markdown)
  VALUES ('memory', 'qa', 'prompt');
  INSERT INTO review_event_v2 (
    id, study_item_id, algorithm, algorithm_version, parameter_version,
    preference, feedback, reviewed_at, due_at_before, due_at_after,
    device_id, created_at
  ) VALUES ('old-v2', 'memory', 'memory_fsrs_6', 2, 1, 'balanced', 3,
    100, 0, 86400, 'legacy-device', 100);
  INSERT INTO review_event_v3 (
    id, study_item_id, algorithm, algorithm_version, parameter_version,
    parameter_checksum, preference, feedback, reviewed_at, duration_quality,
    client_timezone_offset_minutes, due_at_before, due_at_after,
    decision_snapshot_json, device_id, created_at
  ) VALUES ('old-v3', 'memory', 'memory_fsrs_6', 3, 2,
    'bd98e3fdf07a9223a39b5305fe5c14e8d9a03013ddbbce3f5d9ea15555c9c177',
    'balanced', 2, 200, 'unknown', 0, 86400, 172800, '{}', 'legacy-device', 200);
`);
db.exec(read('../migrations/004_v0_4.sql'));

assert.equal(db.prepare('PRAGMA user_version').get().user_version, 4);
assert.equal(db.prepare('SELECT schema_version FROM schema_metadata').get().schema_version, 4);
assert.deepEqual(db.prepare(`
  SELECT action_id, feedback, source_generation FROM review_action_v4 ORDER BY reviewed_at
`).all().map(row => ({ ...row })), [
  { action_id: 'v2:old-v2', feedback: 3, source_generation: 2 },
  { action_id: 'v3:old-v3', feedback: 2, source_generation: 3 },
]);
assert.equal(db.prepare('SELECT COUNT(DISTINCT device_counter) count FROM review_action_v4').get().count, 2);
assert.throws(() => db.prepare(`UPDATE review_action_v4 SET feedback = 4`).run(), /immutable/);
assert.equal(db.prepare(`SELECT dirty FROM schedule_cache_v4 WHERE study_item_id = 'memory'`).get().dirty, 1);

db.exec(`
  INSERT INTO local_device VALUES (1, '00000000-0000-4000-8000-000000000001', NULL, NULL, 2, 1);
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) VALUES ('00000000-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-000000000001', 1, 0, 0,
    'reviewAction', 'new-action', 'create', '{"feedback":3}', 300);
`);
db.exec(`
  UPDATE memory_card SET answer_markdown = 'answer' WHERE study_item_id = 'memory';
  INSERT INTO tag VALUES ('tag', 'sync', 301, 301, NULL);
  INSERT INTO study_item_tag VALUES ('memory', 'tag');
  DELETE FROM study_item_tag WHERE study_item_id = 'memory' AND tag_id = 'tag';
`);
assert.deepEqual(db.prepare(`
  SELECT entity_type, action FROM sync_outbox WHERE device_counter > 1 ORDER BY device_counter
`).all().map(row => ({ ...row })), [
  { entity_type: 'memoryCard', action: 'update' },
  { entity_type: 'relation', action: 'add' },
  { entity_type: 'relation', action: 'remove' },
]);
assert.equal(JSON.parse(db.prepare(`
  SELECT observed_adds_json FROM relation_operation WHERE action = 'remove'
`).get().observed_adds_json).length, 1);
assert.throws(() => db.prepare(`
  INSERT INTO sync_outbox VALUES ('duplicate',
    '00000000-0000-4000-8000-000000000001', 1, 0, 0, 'reviewAction',
    'other', 'create', '{}', 300, 0, 0, NULL)
`).run(), /UNIQUE/);
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
db.close();
console.log('SQLite v3 to v4 migration tests passed');
