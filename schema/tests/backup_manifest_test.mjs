import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const schema = JSON.parse(readFileSync(
  new URL('../backup-v1.schema.json', import.meta.url), 'utf8',
));
const schemaV2 = JSON.parse(readFileSync(
  new URL('../backup-v2.schema.json', import.meta.url), 'utf8',
));
const schemaV3 = JSON.parse(readFileSync(
  new URL('../backup-v3.schema.json', import.meta.url), 'utf8',
));
assert.equal(schema.properties.format.const, 'reviewfault-backup');
assert.equal(schema.properties.version.const, 1);
assert.equal(schema.properties.schemaVersion.const, 1);
assert.equal(schema.properties.schedulerAbiVersion.const, 1);
assert.deepEqual(new Set(schema.required), new Set([
  'format', 'version', 'schemaVersion', 'schedulerAbiVersion', 'exportedAt', 'files',
]));

const pathPattern = new RegExp(schema.properties.files.items.properties.path.pattern);
const hashPattern = new RegExp(schema.properties.files.items.properties.sha256.pattern);
assert(pathPattern.test('database.sqlite'));
assert(pathPattern.test('media/ab/cd.jpg'));
assert(!pathPattern.test('../reviewfault.db'));
assert(!pathPattern.test('media/../secret'));
assert(!pathPattern.test('media/../../secret'));
assert(hashPattern.test('a'.repeat(64)));
assert(!hashPattern.test('A'.repeat(64)));

assert.equal(schemaV2.properties.version.const, 2);
assert.equal(schemaV2.properties.schemaVersion.const, 2);
assert.equal(schemaV2.properties.schedulerAbiVersion.const, 2);
assert(schemaV2.required.includes('appVersion'));
const appVersionPattern = new RegExp(schemaV2.properties.appVersion.pattern);
assert(appVersionPattern.test('0.2.0'));
assert(appVersionPattern.test('0.2.1-rc1'));
assert(!appVersionPattern.test('0.1.0'));

assert.equal(schemaV3.properties.version.const, 3);
assert.equal(schemaV3.properties.schemaVersion.const, 3);
assert.equal(schemaV3.properties.schedulerAbiVersion.const, 3);
const appVersionPatternV3 = new RegExp(schemaV3.properties.appVersion.pattern);
assert(appVersionPatternV3.test('0.3.0'));
assert(!appVersionPatternV3.test('0.2.3'));

const implementations = [
  '../../apps/android/app/src/main/java/cn/reviewfault/app/data/AppDatabase.kt',
  '../../apps/windows/ReviewFault/Data/AppRepository.cs',
];
for (const relative of implementations) {
  const source = readFileSync(new URL(relative, import.meta.url), 'utf8');
  for (const token of [
    'reviewfault-backup', 'database.sqlite', 'schemaVersion',
    'schedulerAbiVersion', 'sha256', 'integrity_check', 'foreign_key_check',
  ]) {
    assert(source.includes(token), `${relative} must implement backup token ${token}`);
  }
}

console.log('Backup manifest contract tests passed');
