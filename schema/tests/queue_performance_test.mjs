import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { DatabaseSync } from 'node:sqlite';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const db = new DatabaseSync(':memory:');
db.exec(read('../migrations/001_initial.sql'));
db.exec(read('../migrations/002_v0_2.sql'));
db.exec(read('../migrations/003_v0_3.sql'));
const insertItem = db.prepare(`
  INSERT INTO study_item (
    id, kind, subject, scheduler_state, difficulty, stability_days, due_at,
    last_reviewed_at, repetitions, created_at, updated_at
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
`);
const insertMemory = db.prepare(`
  INSERT INTO memory_card (study_item_id, template_type, prompt_markdown)
  VALUES (?, 'qa', ?)
`);
const insertMath = db.prepare(`
  INSERT INTO math_problem (study_item_id, prompt_markdown) VALUES (?, ?)
`);
db.exec('BEGIN');
for (let index = 0; index < 5000; index++) {
  const id = `item-${index}`;
  const memory = index % 2 === 0;
  const reviewed = index % 3 === 0;
  insertItem.run(id, memory ? 'memory_card' : 'math_problem',
    memory ? 'operating_systems' : 'math', reviewed ? 2 : 0,
    reviewed ? 5 : 0, reviewed ? 7 : 0, reviewed ? 1_799_999_000 + index : 0,
    reviewed ? 1_799_000_000 : 0, reviewed ? 1 : 0);
  if (memory) insertMemory.run(id, `memory ${index}`);
  else insertMath.run(id, `math ${index}`);
}
db.exec('COMMIT');

const start = performance.now();
const rows = db.prepare(`
  SELECT s.id FROM study_item s
  JOIN schedule_state_v2 q ON q.study_item_id = s.id
  WHERE s.deleted_at IS NULL AND s.suspended_at IS NULL
  ORDER BY CASE WHEN q.due_at > 0 AND q.due_at <= 1800050000 THEN 0 ELSE 1 END,
           q.due_at, s.created_at LIMIT 50
`).all();
const elapsed = performance.now() - start;
assert.equal(rows.length, 50);
assert(elapsed < 2000, `5,000 item queue generation exceeded budget: ${elapsed.toFixed(1)} ms`);
db.close();
console.log(`5,000 item queue performance passed in ${elapsed.toFixed(1)} ms`);
