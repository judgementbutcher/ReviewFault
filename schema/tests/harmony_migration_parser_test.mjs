import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

function harmonyStatements(script) {
  const result = [];
  let current = '';
  let trigger = false;
  for (const raw of script.split('\n')) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('--') ||
        line.toUpperCase().startsWith('PRAGMA FOREIGN_KEYS') ||
        line.toUpperCase().startsWith('PRAGMA USER_VERSION') ||
        line.toUpperCase() === 'BEGIN IMMEDIATE;' || line.toUpperCase() === 'COMMIT;') continue;
    if (line.toUpperCase().startsWith('CREATE TRIGGER')) trigger = true;
    current += `${raw}\n`;
    const complete = trigger ? line.toUpperCase() === 'END;' : line.endsWith(';');
    if (complete) {
      result.push(current.trim().replace(/;$/, ''));
      current = '';
      trigger = false;
    }
  }
  assert.equal(current.trim(), '', 'Harmony migration parser left an incomplete statement');
  return result;
}

const db = new DatabaseSync(':memory:');
for (const name of ['001_initial.sql', '002_v0_2.sql', '003_v0_3.sql', '004_v0_4.sql']) {
  const script = readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8');
  const statements = harmonyStatements(script);
  assert(statements.length > 0, `${name} produced no Harmony Rdb statements`);
  db.exec('BEGIN');
  try {
    for (const statement of statements) db.exec(statement);
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    throw new Error(`Harmony Rdb statement split failed for ${name}: ${error.message}`, { cause: error });
  }
}
assert.equal(db.prepare('SELECT schema_version FROM schema_metadata').get().schema_version, 4);
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length, 0);
db.close();
console.log('Harmony Rdb v1 to v4 statement parser tests passed');
