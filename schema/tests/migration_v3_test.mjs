import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const initial = read('../migrations/001_initial.sql');
const v2 = read('../migrations/002_v0_2.sql');
const v3 = read('../migrations/003_v0_3.sql');
const db = new DatabaseSync(':memory:');
db.exec(initial);
db.exec(v2);
db.exec(`
  INSERT INTO study_item (id, kind, subject, created_at, updated_at)
  VALUES ('memory', 'memory_card', 'operating_systems', 1, 1);
  INSERT INTO memory_card (study_item_id, template_type, prompt_markdown)
  VALUES ('memory', 'qa', 'prompt');
`);
db.exec(v3);

assert.equal(db.prepare('PRAGMA user_version').get().user_version, 3);
assert.equal(db.prepare('SELECT schema_version FROM schema_metadata').get().schema_version, 3);
assert.equal(db.prepare('SELECT scheduler_generation FROM learning_preferences').get().scheduler_generation, 3);
assert.equal(db.prepare('SELECT COUNT(*) count FROM algorithm_parameter_registry').get().count, 5);
assert.deepEqual({ ...db.prepare(`
  SELECT active_algorithm_version, active_parameter_version
  FROM schedule_state_v2 WHERE study_item_id = 'memory'
`).get() }, { active_algorithm_version: 2, active_parameter_version: 1 });

db.prepare(`
  INSERT INTO review_event_v3 (
    id, study_item_id, algorithm, algorithm_version, parameter_version,
    parameter_checksum, preference, feedback, reviewed_at, duration_seconds,
    duration_quality, client_timezone_offset_minutes, due_at_before, due_at_after,
    decision_flags, decision_snapshot_json, device_id, created_at
  ) VALUES ('e3', 'memory', 'memory_fsrs_6', 3, 2,
    'bd98e3fdf07a9223a39b5305fe5c14e8d9a03013ddbbce3f5d9ea15555c9c177',
    'balanced', 3, 100, 20, 'reliable', 480, 0, 86400, 0, '{}', 'device', 100)
`).run();
db.prepare(`
  INSERT INTO memory_review_event_v3 VALUES
    ('e3', 0, 2, 0.90, 0, 1, 0, 0, 5, 0, 1, 0, 0, 0)
`).run();
assert.throws(() => db.prepare("UPDATE review_event_v3 SET feedback = 4 WHERE id = 'e3'").run(), /immutable/);
assert.equal(db.prepare('SELECT COUNT(*) count FROM review_event_v2').get().count, 0,
  'v3 migration preserves the v2 log without rewriting it');
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
db.close();

const rollback = new DatabaseSync(':memory:');
rollback.exec(initial);
rollback.exec(v2);
rollback.exec('CREATE TABLE algorithm_parameter_registry (broken INTEGER)');
assert.throws(() => rollback.exec(v3));
rollback.exec('ROLLBACK');
assert.equal(rollback.prepare('PRAGMA user_version').get().user_version, 2);
assert.equal(rollback.prepare(`SELECT COUNT(*) count FROM pragma_table_info('learning_preferences')
  WHERE name = 'scheduler_generation'`).get().count, 0);
rollback.close();
console.log('SQLite v2 to v3 migration tests passed');
