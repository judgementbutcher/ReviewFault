#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

export function parseReplayTsv(text) {
  const lines = text.trim().split(/\r?\n/);
  if (lines.length < 2) return [];
  const header = lines[0].split('\t');
  return lines.slice(1).filter(Boolean).map((line) => {
    const values = line.split('\t');
    return Object.fromEntries(header.map((key, index) => [key, values[index]]));
  });
}

export function evaluateReplay(rows) {
  const normalized = rows.map((row) => ({
    kind: row.kind,
    reviewedAt: Number(row.reviewed_at ?? row.reviewedAt),
    success: Number(row.success) !== 0,
    predicted: Math.max(0, Math.min(1, Number(row.predicted_recall ?? row.predicted))),
    scheduledDays: Number(row.scheduled_days ?? row.scheduledDays),
  }));
  const total = normalized.length;
  const successes = normalized.filter((row) => row.success).length;
  const daily = new Map();
  for (const row of normalized) {
    const day = Math.floor(row.reviewedAt / 86_400);
    daily.set(day, (daily.get(day) ?? 0) + 1);
  }
  const intervals = normalized.map((row) => row.scheduledDays).sort((a, b) => a - b);
  const percentile = (fraction) => intervals.length === 0 ? 0 :
    intervals[Math.min(intervals.length - 1, Math.floor((intervals.length - 1) * fraction))];
  const brier = total === 0 ? 0 : normalized.reduce((sum, row) => {
    const observed = row.success ? 1 : 0;
    return sum + (row.predicted - observed) ** 2;
  }, 0) / total;
  return {
    eventCount: total,
    recallRate: total === 0 ? 0 : successes / total,
    forgettingCount: total - successes,
    peakDailyLoad: Math.max(0, ...daily.values()),
    intervalDays: {
      minimum: intervals[0] ?? 0,
      p50: percentile(0.50),
      p90: percentile(0.90),
      maximum: intervals.at(-1) ?? 0,
    },
    calibrationErrorBrier: brier,
    byKind: Object.fromEntries(['memory', 'math'].map((kind) => {
      const subset = normalized.filter((row) => row.kind === kind);
      const recalled = subset.filter((row) => row.success).length;
      return [kind, {
        eventCount: subset.length,
        recallRate: subset.length === 0 ? 0 : recalled / subset.length,
      }];
    })),
  };
}

function rowsFromDatabase(path) {
  const db = new DatabaseSync(path, { readOnly: true });
  try {
    return db.prepare(`
      SELECT 'memory' kind, e.reviewed_at,
             CASE WHEN e.feedback > 1 THEN 1 ELSE 0 END success,
             m.retrievability_before predicted_recall, m.scheduled_days
      FROM review_event_v2 e JOIN memory_review_event_v2 m ON m.review_event_id = e.id
      UNION ALL
      SELECT 'memory', e.reviewed_at, CASE WHEN e.feedback > 1 THEN 1 ELSE 0 END,
             m.retrievability_before, m.scheduled_days
      FROM review_event_v3 e JOIN memory_review_event_v3 m ON m.review_event_id = e.id
      UNION ALL
      SELECT 'math', e.reviewed_at, CASE WHEN m.applied_feedback > 1 THEN 1 ELSE 0 END,
             MIN(0.98, 0.35 + m.mastery_before * 0.10), m.scheduled_days
      FROM review_event_v2 e JOIN math_review_event_v2 m ON m.review_event_id = e.id
      UNION ALL
      SELECT 'math', e.reviewed_at, CASE WHEN m.applied_feedback > 1 THEN 1 ELSE 0 END,
             MIN(0.98, 0.35 + m.mastery_before * 0.10), m.scheduled_days
      FROM review_event_v3 e JOIN math_review_event_v3 m ON m.review_event_id = e.id
      ORDER BY reviewed_at
    `).all().map((row) => ({ ...row }));
  } finally {
    db.close();
  }
}

function main() {
  const args = process.argv.slice(2);
  const value = (name) => {
    const index = args.indexOf(name);
    return index < 0 ? undefined : args[index + 1];
  };
  const database = value('--database');
  const input = value('--input');
  const output = value('--output');
  if ((database ? 1 : 0) + (input ? 1 : 0) !== 1) {
    throw new Error('用法：replay-evaluate.mjs (--database DB | --input TSV) [--output JSON]');
  }
  const rows = database ? rowsFromDatabase(database) : parseReplayTsv(readFileSync(input, 'utf8'));
  const report = JSON.stringify({
    format: 'reviewfault-anonymous-replay-v1',
    generatedAt: new Date().toISOString(),
    source: database ? 'local-database' : 'anonymous-tsv',
    metrics: evaluateReplay(rows),
  }, null, 2) + '\n';
  if (output) writeFileSync(output, report);
  else process.stdout.write(report);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
