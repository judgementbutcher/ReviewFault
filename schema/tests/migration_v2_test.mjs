import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const initial = readFileSync(new URL('../migrations/001_initial.sql', import.meta.url), 'utf8');
const migration = readFileSync(new URL('../migrations/002_v0_2.sql', import.meta.url), 'utf8');
const db = new DatabaseSync(':memory:');
db.exec(initial);

const now = 1_800_000_000;
db.prepare(`
  INSERT INTO study_item (
    id, kind, subject, scheduler_state, difficulty, stability_days, due_at,
    last_reviewed_at, repetitions, lapses, created_at, updated_at
  ) VALUES (?, 'memory_card', 'operating_systems', 2, 5, 7, ?, ?, 4, 1, ?, ?)
`).run('memory-old', now + 12345, now - 604800, now - 100000, now - 100000);
db.prepare(`
  INSERT INTO memory_card (study_item_id, template_type, prompt_markdown, answer_markdown)
  VALUES ('memory-old', 'qa', '旧卡片', '旧答案')
`).run();
db.prepare(`
  INSERT INTO study_item (id, kind, subject, created_at, updated_at)
  VALUES ('math-new', 'math_problem', 'math', ?, ?)
`).run(now, now);
db.prepare(`
  INSERT INTO math_problem (study_item_id, prompt_markdown)
  VALUES ('math-new', '一道数学题')
`).run();

db.exec(migration);
assert.equal(db.prepare('PRAGMA user_version').get().user_version, 2);
assert.equal(db.prepare('SELECT schema_version FROM schema_metadata').get().schema_version, 2);

const preferences = db.prepare('SELECT * FROM learning_preferences').get();
assert.equal(preferences.daily_new_memory_limit, 20);
assert.equal(preferences.session_minutes, 20);
assert.equal(preferences.memory_preset, 'balanced');
assert.equal(preferences.math_intensity, 'balanced');

const migrated = db.prepare(`
  SELECT algorithm, due_at, repetitions, needs_history_replay
  FROM schedule_state_v2 WHERE study_item_id = 'memory-old'
`).get();
assert.deepEqual({ ...migrated }, {
  algorithm: 'memory_fsrs_6',
  due_at: now + 12345,
  repetitions: 4,
  needs_history_replay: 1,
}, 'v1 due time is retained and mature history is marked for lazy replay');
assert.equal(
  db.prepare("SELECT difficulty FROM memory_schedule_state WHERE study_item_id = 'memory-old'").get().difficulty,
  5,
);
assert.deepEqual(
  { ...db.prepare("SELECT mastery_level, fluent_streak FROM math_schedule_state WHERE study_item_id = 'math-new'").get() },
  { mastery_level: 0, fluent_streak: 0 },
);

db.prepare(`
  INSERT INTO review_event_v2 (
    id, study_item_id, algorithm, algorithm_version, parameter_version,
    preference, feedback, reviewed_at, due_at_before, due_at_after,
    device_id, created_at
  ) VALUES ('event-memory', 'memory-old', 'memory_fsrs_6', 2, 1,
    'balanced', 3, ?, ?, ?, 'device', ?)
`).run(now, now, now + 86400, now);
db.prepare(`
  INSERT INTO memory_review_event_v2 (
    review_event_id, state_before, state_after, target_retention,
    elapsed_days, scheduled_days, retrievability_before,
    difficulty_before, difficulty_after, stability_before, stability_after
  ) VALUES ('event-memory', 2, 2, 0.90, 7, 10, 0.9, 5, 4.8, 7, 10)
`).run();
assert.throws(
  () => db.prepare("UPDATE review_event_v2 SET feedback = 4 WHERE id = 'event-memory'").run(),
  /immutable/i,
);
assert.throws(
  () => db.prepare("DELETE FROM memory_review_event_v2 WHERE review_event_id = 'event-memory'").run(),
  /immutable/i,
);

db.prepare('UPDATE study_item SET deleted_at = ? WHERE id = ?').run(now, 'memory-old');
assert.equal(db.prepare(`
  SELECT COUNT(*) AS count FROM study_item WHERE deleted_at IS NULL AND id = 'memory-old'
`).get().count, 0, 'soft-deleted content leaves ordinary library results');
assert.equal(db.prepare(`
  SELECT COUNT(*) AS count FROM review_event_v2 WHERE study_item_id = 'memory-old'
`).get().count, 1, 'soft delete preserves immutable review history');
db.prepare("UPDATE study_item SET deleted_at = NULL WHERE id = 'memory-old'").run();
assert.equal(db.prepare("SELECT deleted_at FROM study_item WHERE id = 'memory-old'").get().deleted_at, null);

// Migration runners may defensively retry the same step after a crash. All v2
// DDL and seed/copy operations are idempotent.
db.exec(migration);
assert.equal(db.prepare('SELECT COUNT(*) AS count FROM schedule_state_v2').get().count, 2);
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
db.close();

const rollbackDb = new DatabaseSync(':memory:');
rollbackDb.exec(initial);
rollbackDb.exec('CREATE TABLE review_event_v2 (broken INTEGER)');
assert.throws(() => rollbackDb.exec(migration), /no such column|has no column/i,
  'a conflicting schema aborts the migration');
rollbackDb.exec('ROLLBACK');
assert.equal(rollbackDb.prepare('PRAGMA user_version').get().user_version, 1,
  'failed migration leaves the database at v1');
assert.equal(rollbackDb.prepare(`
  SELECT COUNT(*) AS count FROM sqlite_master
  WHERE type = 'table' AND name = 'learning_preferences'
`).get().count, 0, 'transaction rollback removes partial v2 tables');
rollbackDb.close();
console.log('SQLite v1 to v2 migration tests passed');
