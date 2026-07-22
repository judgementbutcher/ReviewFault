import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const destination = resolve(root, 'apps/harmony/entry/src/main/resources/rawfile/contracts');
mkdirSync(destination, { recursive: true });
for (const file of [
  'schema/migrations/001_initial.sql',
  'schema/migrations/002_v0_2.sql',
  'schema/migrations/003_v0_3.sql',
  'schema/migrations/004_v0_4.sql',
  'schema/migrations/005_v0_5.sql',
  'schema/sync-operation-v1.schema.json',
  'schema/reviewfault-ink-v1.schema.json',
]) copyFileSync(resolve(root, file), resolve(destination, file.split('/').at(-1)));
console.log('Harmony rawfile contracts prepared');
