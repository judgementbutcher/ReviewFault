import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const migration = readFileSync(
  new URL('../migrations/001_initial.sql', import.meta.url),
  'utf8',
);
const migrationV2 = readFileSync(
  new URL('../migrations/002_v0_2.sql', import.meta.url), 'utf8',
);

const db = new DatabaseSync(':memory:');
db.exec(migration);

// Android's SQLiteDatabase.execSQL accepts one statement at a time. Keep this
// parser behavior aligned with AppDatabase.migrationStatements.
function androidMigrationStatements(script) {
  const statements = [];
  let current = '';
  let inTrigger = false;
  for (const rawLine of script.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('--') ||
        /^PRAGMA foreign_keys/i.test(line) || /^PRAGMA user_version/i.test(line) ||
        /^BEGIN IMMEDIATE;$/i.test(line) || /^COMMIT;$/i.test(line)) {
      continue;
    }
    if (/^CREATE TRIGGER/i.test(line)) inTrigger = true;
    current += `${rawLine}\n`;
    const complete = inTrigger ? /END;$/i.test(line) : line.endsWith(';');
    if (complete) {
      statements.push(current.trim().replace(/;$/, ''));
      current = '';
      inTrigger = false;
    }
  }
  assert.equal(current.trim(), '', 'Android migration parser consumes the full script');
  return statements;
}

const androidDb = new DatabaseSync(':memory:');
androidDb.exec('PRAGMA foreign_keys = ON');
for (const statement of androidMigrationStatements(migration)) {
  androidDb.exec(statement);
}
assert.equal(
  androidDb.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'table'").get().count,
  13,
  'Android one-statement execution creates the complete schema',
);
assert.equal(
  androidDb.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'trigger'").get().count,
  4,
  'Android one-statement execution also creates all triggers',
);
for (const statement of androidMigrationStatements(migrationV2)) {
  androidDb.exec(statement);
}
assert.equal(androidDb.prepare('PRAGMA user_version').get().user_version, 0,
  'platform parser leaves user_version management to SQLiteOpenHelper');
assert.equal(
  androidDb.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'table'").get().count,
  20,
  'Android sequential execution creates all schema v2 tables',
);
assert.equal(
  androidDb.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'trigger'").get().count,
  12,
  'Android sequential execution creates v2 schedule and immutability triggers',
);
androidDb.close();

assert.equal(db.prepare('PRAGMA user_version').get().user_version, 1);
assert.equal(db.prepare('PRAGMA foreign_keys').get().foreign_keys, 1);

const expectedTables = [
  'attempt',
  'attempt_media',
  'chapter',
  'item_relation',
  'math_problem',
  'math_problem_media',
  'media',
  'memory_card',
  'review_log',
  'schema_metadata',
  'study_item',
  'study_item_tag',
  'tag',
];
const actualTables = db
  .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
  .all()
  .map(({ name }) => name);
assert.deepEqual(actualTables, expectedTables);

const now = 1_800_000_000;
db.prepare(`
  INSERT INTO chapter (id, subject, name, created_at, updated_at)
  VALUES (?, ?, ?, ?, ?)
`).run('chapter-os', 'operating_systems', '进程与线程', now, now);

db.prepare(`
  INSERT INTO study_item (
    id, kind, subject, chapter_id, created_at, updated_at
  ) VALUES (?, ?, ?, ?, ?, ?)
`).run('card-1', 'memory_card', 'operating_systems', 'chapter-os', now, now);
db.prepare(`
  INSERT INTO memory_card (
    study_item_id, template_type, prompt_markdown, answer_markdown, hints_json
  ) VALUES (?, ?, ?, ?, ?)
`).run(
  'card-1',
  'layered_hint',
  '进程与线程共享和独占哪些资源？',
  '线程共享地址空间与打开文件；各自拥有栈和寄存器。',
  '["先按资源类型分类"]',
);

db.prepare(`
  INSERT INTO study_item (
    id, kind, subject, created_at, updated_at
  ) VALUES (?, ?, ?, ?, ?)
`).run('math-1', 'math_problem', 'math', now, now);
db.prepare(`
  INSERT INTO math_problem (
    study_item_id, source_name, prompt_markdown, default_error_reason
  ) VALUES (?, ?, ?, ?)
`).run('math-1', '高等数学例题', '求极限 $\\lim_{x\\to0}\\frac{\\sin x}{x}$', 'concept');
db.prepare(`
  INSERT INTO attempt (
    id, math_problem_id, started_at, finished_at, result,
    confidence, error_reason, created_at
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
`).run('attempt-1', 'math-1', now, now + 180, 'wrong', 2, 'concept', now + 180);

db.prepare(`
  UPDATE study_item SET
    scheduler_state = 2,
    difficulty = 5,
    stability_days = 2,
    due_at = ?,
    last_reviewed_at = ?,
    repetitions = 1,
    updated_at = ?
  WHERE id = ?
`).run(now + 172_800, now, now, 'card-1');
db.prepare(`
  INSERT INTO review_log (
    id, study_item_id, reviewed_at, rating, scheduler_abi_version,
    state_before, state_after, elapsed_days, scheduled_days,
    retrievability_before, difficulty_before, difficulty_after,
    stability_before, stability_after, due_at_after, device_id, created_at
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`).run(
  'review-1', 'card-1', now, 3, 1,
  0, 2, 0, 2, 0, 0, 5, 0, 2, now + 172_800, 'device-local', now,
);

const due = db.prepare(`
  SELECT id FROM study_item
  WHERE scheduler_state <> 0 AND suspended_at IS NULL AND due_at <= ?
  ORDER BY due_at
`).all(now + 172_800);
assert.deepEqual(due.map(({ id }) => id), ['card-1']);

assert.throws(
  () => db.prepare(`
    INSERT INTO review_log (
      id, study_item_id, reviewed_at, rating, scheduler_abi_version,
      state_before, state_after, elapsed_days, scheduled_days,
      retrievability_before, difficulty_before, difficulty_after,
      stability_before, stability_after, due_at_after, device_id, created_at
    ) VALUES ('bad-rating', 'card-1', ?, 5, 1, 2, 2, 2, 2, 0.9, 5, 5, 2, 3, ?, 'd', ?)
  `).run(now, now, now),
  /constraint failed/i,
);

assert.throws(
  () => db.prepare(`
    INSERT INTO memory_card (study_item_id, template_type, prompt_markdown)
    VALUES ('missing-item', 'qa', 'orphan')
  `).run(),
  /memory_card requires a memory_card study_item/i,
);

assert.throws(
  () => db.prepare(`
    INSERT INTO math_problem (study_item_id, prompt_markdown)
    VALUES ('card-1', 'wrong detail type')
  `).run(),
  /requires a math_problem study_item/i,
);

assert.throws(
  () => db.prepare(`
    UPDATE review_log SET rating = 4 WHERE id = 'review-1'
  `).run(),
  /review_log is immutable/i,
);

assert.throws(
  () => db.prepare("DELETE FROM review_log WHERE id = 'review-1'").run(),
  /review_log is immutable/i,
);

assert.equal(
  db.prepare('SELECT COUNT(*) AS count FROM review_log').get().count,
  1,
  'failed inserts do not damage the immutable history',
);

db.close();
console.log('SQLite migration tests passed');
