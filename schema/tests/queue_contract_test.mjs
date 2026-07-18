import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const initial = read('../migrations/001_initial.sql');
const migration = read('../migrations/002_v0_2.sql');
const migrationV3 = read('../migrations/003_v0_3.sql');

const sources = [
  {
    name: 'Android',
    text: read('../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt'),
    method(name) {
      return this.text.match(new RegExp(`fun ${name}\\([\\s\\S]*?rawQuery\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"`))[1];
    },
  },
  {
    name: 'Windows',
    text: read('../../apps/windows/ReviewFault/Data/AppRepository.cs'),
    method(name) {
      const methodName = name[0].toUpperCase() + name.slice(1);
      const sql = this.text.match(new RegExp(`${methodName}Async\\([\\s\\S]*?CommandText = \"\"\"([\\s\\S]*?)\"\"\"`))[1];
      return sql.replaceAll('$tomorrowStart', '?').replaceAll('$tomorrowEnd', '?')
        .replaceAll('$weekEnd', '?').replaceAll('$includeNewItems', '?')
        .replaceAll('$excludedItemIds', '?').replaceAll('$now', '?')
        .replaceAll('$dayStart', '?');
    },
  },
];

const now = 1_800_050_000;
const dayStart = 1_800_000_000;

function addItem(db, id, kind, subject, state = 0, dueAt = 0, extra = '') {
  const reviewed = state === 0 ? 0 : dayStart - 86_400;
  const difficulty = state === 0 ? 0 : 5;
  const stability = state === 0 ? 0 : 7;
  db.prepare(`
    INSERT INTO study_item (
      id, kind, subject, scheduler_state, difficulty, stability_days, due_at,
      last_reviewed_at, repetitions, lapses, created_at, updated_at ${extra ? `, ${extra.split('=')[0]}` : ''}
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ? ${extra ? ', ?' : ''})
  `).run(id, kind, subject, state, difficulty, stability, dueAt, reviewed,
    state === 0 ? 0 : 1, dayStart - 1000, dayStart - 1000,
    ...(extra ? [Number(extra.split('=')[1])] : []));
}

function addNewMemoryReview(db, eventId, itemId) {
  db.prepare(`
    INSERT INTO review_event_v2 (
      id, study_item_id, algorithm, algorithm_version, parameter_version,
      preference, feedback, reviewed_at, due_at_before, due_at_after,
      device_id, created_at
    ) VALUES (?, ?, 'memory_fsrs_6', 2, 1, 'balanced', 3, ?, 0, ?, 'test', ?)
  `).run(eventId, itemId, now, now + 86_400, now);
  db.prepare(`
    INSERT INTO memory_review_event_v2 (
      review_event_id, state_before, state_after, target_retention,
      elapsed_days, scheduled_days, retrievability_before,
      difficulty_before, difficulty_after, stability_before, stability_after
    ) VALUES (?, 0, 1, 0.90, 0, 1, 0, 0, 5, 0, 1)
  `).run(eventId);
}

function encodedExcluded(...ids) {
  return ids.length === 0 ? '' : `|${ids.sort().join('|')}|`;
}

function nextParameters(excluded, includeNewItems = 1) {
  return [excluded, excluded, now, includeNewItems,
    dayStart, dayStart, dayStart, dayStart];
}

for (const source of sources) {
  const db = new DatabaseSync(':memory:');
  db.exec(initial);
  db.exec(migration);
  db.exec(migrationV3);
  db.exec(`
    UPDATE learning_preferences SET daily_new_memory_limit = 2,
      enable_computer_networks = 0, include_memory_cards = 1,
      include_math_problems = 1 WHERE singleton = 1;
  `);
  addItem(db, 'memory-new-a', 'memory_card', 'operating_systems');
  addItem(db, 'memory-new-b', 'memory_card', 'operating_systems');
  addItem(db, 'memory-new-c', 'memory_card', 'operating_systems');
  addItem(db, 'network-new-disabled', 'memory_card', 'computer_networks');
  addItem(db, 'math-new', 'math_problem', 'math');
  addItem(db, 'memory-due', 'memory_card', 'operating_systems', 2, now - 10);
  addItem(db, 'network-due-disabled', 'memory_card', 'computer_networks', 2, now - 10);
  addItem(db, 'math-overdue', 'math_problem', 'math', 2, dayStart - 10);
  addItem(db, 'math-new-deleted', 'math_problem', 'math', 0, 0, 'deleted_at=1');
  addItem(db, 'math-new-suspended', 'math_problem', 'math', 0, 0, 'suspended_at=1');

  const dashboardSql = source.method('dashboard');
  const dashboardParameters = [dayStart, dayStart, now, now, dayStart, dayStart,
    dayStart + 86_400, dayStart + 2 * 86_400, now, now + 7 * 86_400];
  const summary = db.prepare(dashboardSql).get(...dashboardParameters);
  assert.deepEqual(Object.values(summary), [1, 1, 3, 1, 525, 2, 0, 20, 0, 0],
    `${source.name} dashboard must expose only the enabled, actionable queue`);
  assert(source.text.includes('DeferredDueMinutes') || source.text.includes('deferredDueMinutes'),
    `${source.name} dashboard exposes focus-session backlog protection`);
  assert(source.text.includes('NextSevenDaysDue') || source.text.includes('nextSevenDaysDue'),
    `${source.name} dashboard exposes the seven-day load forecast`);

  const nextSql = source.method('nextForReview');
  assert.equal(Object.values(db.prepare(nextSql).get(...nextParameters('')))[0], 'math-overdue',
    `${source.name} must start with an overdue enabled item`);
  assert.equal(Object.values(db.prepare(nextSql).get(
    ...nextParameters(encodedExcluded('math-overdue'))))[0], 'memory-due',
    `${source.name} skips an excluded first item without changing the queue order`);
  assert.equal(db.prepare(nextSql).get(...nextParameters(
    encodedExcluded('math-overdue', 'memory-due'), 0)), undefined,
    `${source.name} returns no item when every due candidate is excluded for the session`);

  addNewMemoryReview(db, 'review-new-a', 'memory-new-a');
  addNewMemoryReview(db, 'review-new-b', 'memory-new-b');
  db.exec(`UPDATE study_item SET due_at = ${now + 1000}
    WHERE id IN ('memory-due', 'math-overdue')`);
  assert.equal(Object.values(db.prepare(nextSql).get(...nextParameters('')))[0], 'math-new',
    `${source.name} keeps new math available after the daily 408 limit is reached`);
  assert.equal(db.prepare(nextSql).get(...nextParameters('', 0)), undefined,
    `${source.name} freezes backlog protection for the whole focus session`);

  db.exec(`UPDATE learning_preferences SET include_math_problems = 0 WHERE singleton = 1`);
  assert.equal(db.prepare(nextSql).get(...nextParameters('')), undefined,
    `${source.name} applies queue-type and daily-limit preferences`);
  db.close();
}

console.log('Cross-platform queue contract tests passed');
