import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const db = new DatabaseSync(':memory:');
for (const file of ['001_initial.sql', '002_v0_2.sql', '003_v0_3.sql', '004_v0_4.sql'])
  db.exec(read(`../migrations/${file}`));
db.exec(`INSERT INTO study_item (id, kind, subject, due_at, created_at, updated_at)
  VALUES ('math', 'math_problem', 'math', 12345, 1, 1);
  INSERT INTO math_problem (study_item_id) VALUES ('math');
  INSERT INTO study_item (id, kind, subject, due_at, created_at, updated_at)
  VALUES ('memory', 'memory_card', 'operating_systems', 23456, 1, 1);
  INSERT INTO memory_card (study_item_id, template_type, prompt_markdown) VALUES ('memory', 'qa', 'p');`);
db.exec(read('../migrations/005_v0_5.sql'));

assert.equal(db.prepare('PRAGMA user_version').get().user_version, 5);
assert.equal(db.prepare('SELECT schema_version FROM schema_metadata').get().schema_version, 5);
assert.equal(db.prepare('SELECT exam_at FROM learning_profile_v5').get().exam_at, 1797724800);
assert.deepEqual(db.prepare(`SELECT id, unit_type FROM learning_unit_v5 ORDER BY id`).all().map(x => ({ ...x })), [
  { id: 'v5-unit:math', unit_type: 'math_error_cluster' },
  { id: 'v5-unit:memory', unit_type: 'memory_knowledge_package' },
]);
assert.deepEqual(db.prepare(`SELECT id, task_type, task_state, due_at, legacy_due_at FROM learning_task_v5 ORDER BY id`).all().map(x => ({ ...x })), [
  { id: 'v5-task:math', task_type: 'math_original', task_state: 'legacy', due_at: 12345, legacy_due_at: 12345 },
  { id: 'v5-task:memory', task_type: 'memory_recall', task_state: 'legacy', due_at: 23456, legacy_due_at: 23456 },
]);
assert.deepEqual(db.prepare(`SELECT study_item_id, archetype, source_type, source_title
  FROM card_profile_v5 ORDER BY study_item_id`).all().map(x => ({ ...x })), [
  { study_item_id: 'math', archetype: 'math_error', source_type: 'practice', source_title: '' },
  { study_item_id: 'memory', archetype: 'qa', source_type: 'notes', source_title: '' },
]);
db.exec(`INSERT INTO study_item (id, kind, subject, due_at, created_at, updated_at)
  VALUES ('new-math', 'math_problem', 'math', 34567, 2, 2);`);
db.exec(`INSERT INTO math_problem (study_item_id, source_name) VALUES ('new-math', '张宇 1000 题');`);
assert.deepEqual({ ...db.prepare(`SELECT archetype, source_title FROM card_profile_v5
  WHERE study_item_id = 'new-math'`).get() }, { archetype: 'math_error', source_title: '张宇 1000 题' });
assert.throws(() => db.exec(`UPDATE card_profile_v5 SET structured_payload_json = 'not-json'
  WHERE study_item_id = 'new-math'`), /constraint/i);
assert.deepEqual({ ...db.prepare(`SELECT task_type, task_state, math_phase, due_at, legacy_due_at
  FROM learning_task_v5 WHERE id = 'v5-task:new-math:repair'`).get() }, {
  task_type: 'math_repair', task_state: 'active', math_phase: 'repair', due_at: 34567, legacy_due_at: 0,
});
assert.equal(db.prepare(`SELECT COUNT(*) AS count FROM learning_task_v5
  WHERE source_study_item_id = 'new-math'`).get().count, 1);
db.exec(`INSERT INTO learning_evidence_v5 (evidence_id, learning_task_id, task_type, reviewed_at,
  correct, device_id, device_counter, created_at)
  VALUES ('evidence', 'v5-task:math', 'math_original', 30000, 1, 'device', 1, 30000);`);
assert.throws(() => db.exec(`UPDATE learning_evidence_v5 SET correct = 0`), /immutable/);
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
db.close();
console.log('SQLite v4 to v5 learning-unit migration tests passed');
