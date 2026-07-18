import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { evaluateReplay, parseReplayTsv } from '../../tools/replay-evaluate.mjs';

const rows = parseReplayTsv(readFileSync(
  new URL('../../fixtures/replay_baseline_v2.tsv', import.meta.url), 'utf8',
));
const metrics = evaluateReplay(rows);
assert.equal(metrics.eventCount, 12);
assert.equal(metrics.forgettingCount, 4);
assert.equal(metrics.peakDailyLoad, 3);
assert.equal(metrics.intervalDays.p50, 2.3065);
assert.equal(metrics.intervalDays.p90, 14);
assert(Math.abs(metrics.recallRate - 2 / 3) < 1e-12);
assert(metrics.calibrationErrorBrier > 0 && metrics.calibrationErrorBrier < 1);
console.log('Anonymous replay evaluation tests passed');
