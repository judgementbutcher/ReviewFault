import { relationalStore } from '@kit.ArkData';
import { UIAbilityContext } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import reviewfault from 'libreviewfault.so';

const DATABASE_NAME = 'reviewfault.db';
const SCHEMA_VERSION = 5;

export interface DashboardSummary {
  overdue: number; dueToday: number; newItems: number;
  reviewsToday: number; accuracyPercent: number; streakDays: number; totalReviews: number;
  activeItems: number; masteredItems: number; reviewDays: number[]; dueDays: number[];
  subjects: SubjectInsight[];
}
export interface SubjectInsight {
  subject: string; total: number; mastered: number;
}
export interface StudyItem {
  id: string; kind: string; subject: string; state: number; difficulty: number;
  stabilityDays: number; dueAt: number; lastReviewedAt: number; repetitions: number;
  lapses: number; masteryLevel: number; fluentStreak: number; prompt: string; answer: string;
  templateType: string; hints: string[]; answerPoints: string[]; profile: CardProfile; tags: string[];
}
export interface CardProfile {
  archetype: string; knowledgePoint: string; sourceType: string; sourceTitle: string;
  sourceChapter: string; sourceLocator: string; sourceYear?: number; mechanism: string; conditions: string;
  contrast: string; example: string; commonTrap: string; transferPrompt: string; mnemonic: string;
  firstAttempt: string; errorTrigger: string; generalMethod: string; verification: string;
  targetSeconds?: number; structuredPayload: string;
}
export interface MemoryCardDraft {
  templateType: string; archetype: string; subject: string; knowledgePoint: string;
  prompt: string; answer: string; hints: string[]; answerPoints: string[];
  mechanism: string; conditions: string; contrast: string; example: string;
  commonTrap: string; transferPrompt: string; mnemonic: string; structuredPayload: string;
  sourceType: string; sourceTitle: string; sourceChapter: string; sourceLocator: string;
  sourceYear?: number; tags: string[];
}
export interface MathErrorDraft {
  knowledgePoint: string; prompt: string; solution: string; firstAttempt: string;
  errorTrigger: string; errorReason?: string; keyHint: string; generalMethod: string;
  verification: string; transferPrompt: string; targetSeconds?: number;
  sourceType: string; sourceTitle: string; sourceChapter: string; sourceLocator: string;
  sourceYear?: number; tags: string[];
}
export interface ReviewEvidence {
  hintLevel: number; answerRevealed: boolean; confidence: number; reflection: string;
  pointHits?: number; pointCount?: number;
}
export interface SyncIdentity {
  deviceId: string; workspaceId: string; cursor: number; pendingCount: number;
}
export interface LocalSyncOperation {
  operationId: string; deviceId: string; deviceCounter: number; baseCursor: number;
  baseRevision: number; entityType: string; entityId: string; action: string;
  changedFields: Record<string, Object>; occurredAt: number;
}
export interface PulledOperation {
  operationId: string; serverSeq: number; deviceId: string; deviceCounter: number;
  entityType: string; entityId: string; action: string;
  changedFields: Record<string, Object>; occurredAt: number;
}
interface ReviewFact {
  actionId: string; deviceId: string; deviceCounter: number; causalCursor: number;
  feedback: number; reviewedAt: number; durationSeconds: number; errorReason: string; hintRevealed: boolean;
}

export class LocalDatabase {
  private store?: relationalStore.RdbStore;

  constructor(private readonly context: UIAbilityContext) {}

  async open(): Promise<relationalStore.RdbStore> {
    if (this.store) return this.store;
    const store = await relationalStore.getRdbStore(this.context, {
      name: DATABASE_NAME, securityLevel: relationalStore.SecurityLevel.S1,
    });
    await store.executeSql('PRAGMA foreign_keys = ON');
    const version = await this.version(store);
    if (version > SCHEMA_VERSION) throw new Error(`Unsupported database version ${version}`);
    for (let next = version + 1; next <= SCHEMA_VERSION; next++) await this.applyMigration(store, next);
    const existing = await store.querySql('SELECT COUNT(*) FROM local_device WHERE singleton = 1');
    let hasIdentity = false;
    try { hasIdentity = existing.goToFirstRow() && existing.getLong(0) > 0; } finally { existing.close(); }
    if (!hasIdentity) await store.executeSql(
      'INSERT INTO local_device (singleton, device_id, next_counter, created_at) VALUES (1, ?, 1, ?)',
      [util.generateRandomUUID(true), this.now()],
    );
    this.store = store; return store;
  }

  async dashboard(): Promise<DashboardSummary> {
    const store = await this.open(); const now = this.now();
    const start = new Date(); start.setHours(0, 0, 0, 0); const dayStart = Math.floor(start.getTime() / 1000);
    const result = await store.querySql(`SELECT
      COALESCE(SUM(CASE WHEN i.scheduler_state <> 0 AND i.due_at < ? THEN 1 ELSE 0 END), 0),
      COALESCE(SUM(CASE WHEN i.scheduler_state <> 0 AND i.due_at BETWEEN ? AND ? THEN 1 ELSE 0 END), 0),
      COALESCE(SUM(CASE WHEN i.scheduler_state = 0 THEN 1 ELSE 0 END), 0)
      FROM study_item i WHERE i.deleted_at IS NULL`,
      [dayStart, dayStart, now]);
    let overdue = 0; let dueToday = 0; let newItems = 0;
    try { if (result.goToFirstRow()) {
      overdue = result.getLong(0); dueToday = result.getLong(1); newItems = result.getLong(2);
    } } finally { result.close(); }
    const historyStart = dayStart - 6 * 86400;
    const reviewDays: number[] = [0, 0, 0, 0, 0, 0, 0];
    const reviewResult = await store.querySql(`WITH events AS (
      SELECT reviewed_at FROM review_event_v2 UNION ALL SELECT reviewed_at FROM review_event_v3)
      SELECT CAST((reviewed_at - ?) / 86400 AS INTEGER), COUNT(*) FROM events
      WHERE reviewed_at >= ? AND reviewed_at < ? GROUP BY 1`,
      [historyStart, historyStart, dayStart + 86400]);
    try { if (reviewResult.goToFirstRow()) do {
      const index = reviewResult.getLong(0); if (index >= 0 && index < 7) reviewDays[index] = reviewResult.getLong(1);
    } while (reviewResult.goToNextRow()); } finally { reviewResult.close(); }
    const dueDays: number[] = [0, 0, 0, 0, 0, 0, 0];
    const dueResult = await store.querySql(`SELECT CAST((due_at - ?) / 86400 AS INTEGER), COUNT(*)
      FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL AND scheduler_state <> 0
      AND due_at >= ? AND due_at < ? GROUP BY 1`, [dayStart, dayStart, dayStart + 7 * 86400]);
    try { if (dueResult.goToFirstRow()) do {
      const index = dueResult.getLong(0); if (index >= 0 && index < 7) dueDays[index] = dueResult.getLong(1);
    } while (dueResult.goToNextRow()); } finally { dueResult.close(); }
    let totalReviews = 0; let recentReviews = 0; let recentSuccessful = 0;
    const eventResult = await store.querySql(`WITH events AS (
      SELECT algorithm, feedback, reviewed_at FROM review_event_v2
      UNION ALL SELECT algorithm, feedback, reviewed_at FROM review_event_v3)
      SELECT COUNT(*), COALESCE(SUM(CASE WHEN reviewed_at >= ? THEN 1 ELSE 0 END), 0),
      COALESCE(SUM(CASE WHEN reviewed_at >= ? AND ((algorithm = 'memory_fsrs_6' AND feedback >= 3)
      OR (algorithm = 'math_mastery_ladder' AND feedback >= 2)) THEN 1 ELSE 0 END), 0) FROM events`,
      [historyStart, historyStart]);
    try { if (eventResult.goToFirstRow()) { totalReviews = eventResult.getLong(0);
      recentReviews = eventResult.getLong(1); recentSuccessful = eventResult.getLong(2); }
    } finally { eventResult.close(); }
    const reviewedDates: Set<string> = new Set<string>();
    const datesResult = await store.querySql(`SELECT reviewed_at FROM review_event_v2 WHERE reviewed_at >= ?
      UNION ALL SELECT reviewed_at FROM review_event_v3 WHERE reviewed_at >= ?`,
      [dayStart - 366 * 86400, dayStart - 366 * 86400]);
    try { if (datesResult.goToFirstRow()) do {
      reviewedDates.add(this.dateKey(new Date(datesResult.getLong(0) * 1000)));
    } while (datesResult.goToNextRow()); } finally { datesResult.close(); }
    let streakDate = new Date(); streakDate.setHours(0, 0, 0, 0);
    if (!reviewedDates.has(this.dateKey(streakDate))) streakDate.setDate(streakDate.getDate() - 1);
    let streakDays = 0;
    while (reviewedDates.has(this.dateKey(streakDate)) && streakDays < 366) {
      streakDays += 1; streakDate.setDate(streakDate.getDate() - 1);
    }
    let activeItems = 0; let masteredItems = 0;
    const progressResult = await store.querySql(`SELECT COUNT(*),
      COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
      FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL`);
    try { if (progressResult.goToFirstRow()) { activeItems = progressResult.getLong(0);
      masteredItems = progressResult.getLong(1); } } finally { progressResult.close(); }
    const subjects: SubjectInsight[] = [];
    const subjectResult = await store.querySql(`SELECT subject, COUNT(*),
      COALESCE(SUM(CASE WHEN repetitions >= 3 AND stability_days >= 14 THEN 1 ELSE 0 END), 0)
      FROM study_item WHERE deleted_at IS NULL AND suspended_at IS NULL GROUP BY subject ORDER BY COUNT(*) DESC`);
    try { if (subjectResult.goToFirstRow()) do { subjects.push({ subject: subjectResult.getString(0),
      total: subjectResult.getLong(1), mastered: subjectResult.getLong(2) });
    } while (subjectResult.goToNextRow()); } finally { subjectResult.close(); }
    return { overdue: overdue, dueToday: dueToday, newItems: newItems,
      reviewsToday: reviewDays[6], accuracyPercent: recentReviews === 0 ? 0 :
        Math.floor(recentSuccessful * 100 / recentReviews), streakDays: streakDays,
      totalReviews: totalReviews, activeItems: activeItems, masteredItems: masteredItems,
      reviewDays: reviewDays, dueDays: dueDays, subjects: subjects };
  }

  private dateKey(value: Date): string {
    return `${value.getFullYear()}-${value.getMonth() + 1}-${value.getDate()}`;
  }

  async items(query: string = ''): Promise<StudyItem[]> {
    const store = await this.open(); const pattern = `%${query.trim()}%`;
    const result = await store.querySql(`SELECT i.id, i.kind, i.subject, i.scheduler_state,
      i.difficulty, i.stability_days, i.due_at, i.last_reviewed_at, i.repetitions, i.lapses,
      COALESCE(ms.mastery_level, 0), COALESCE(ms.fluent_streak, 0),
      COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
      COALESCE(m.answer_markdown, p.solution_markdown, ''), COALESCE(m.template_type, ''),
      CASE WHEN m.hints_json IS NOT NULL THEN m.hints_json
        WHEN trim(COALESCE(p.key_hint_markdown, '')) <> '' THEN json_array(p.key_hint_markdown) ELSE '[]' END,
      COALESCE(m.answer_points_json, '[]'),
      COALESCE(cp.archetype, CASE WHEN i.kind = 'math_problem' THEN 'math_error' ELSE 'qa' END),
      COALESCE(cp.knowledge_point, ''), COALESCE(cp.source_type, 'notes'),
      COALESCE(NULLIF(cp.source_title, ''), p.source_name, ''), COALESCE(cp.source_chapter, ''),
      COALESCE(cp.source_locator, ''), COALESCE(cp.mechanism_markdown, ''),
      COALESCE(cp.conditions_markdown, ''), COALESCE(cp.contrast_markdown, ''),
      COALESCE(cp.example_markdown, ''), COALESCE(cp.common_trap_markdown, ''),
      COALESCE(cp.transfer_prompt_markdown, ''), COALESCE(cp.mnemonic, ''),
      COALESCE(NULLIF(cp.first_attempt_markdown, ''), p.wrong_step_markdown, ''),
      COALESCE(cp.error_trigger_markdown, ''), COALESCE(cp.general_method_markdown, ''),
      COALESCE(cp.verification_markdown, ''),
      COALESCE((SELECT json_group_array(tag_name) FROM (SELECT t.name AS tag_name
        FROM study_item_tag sit JOIN tag t ON t.id = sit.tag_id WHERE sit.study_item_id = i.id
        AND t.deleted_at IS NULL ORDER BY t.name)), '[]'),
      COALESCE(cp.source_year, 0), COALESCE(cp.target_seconds, 0),
      COALESCE(cp.structured_payload_json, '{}')
      FROM study_item i LEFT JOIN math_schedule_state ms ON ms.study_item_id = i.id
      LEFT JOIN memory_card m ON m.study_item_id = i.id LEFT JOIN math_problem p ON p.study_item_id = i.id
      LEFT JOIN card_profile_v5 cp ON cp.study_item_id = i.id
      WHERE i.deleted_at IS NULL AND (m.prompt_markdown LIKE ? OR m.answer_markdown LIKE ?
        OR p.source_name LIKE ? OR cp.knowledge_point LIKE ? OR cp.source_title LIKE ?
        OR EXISTS (SELECT 1 FROM study_item_tag qit JOIN tag qt ON qt.id = qit.tag_id
          WHERE qit.study_item_id = i.id AND qt.name LIKE ?))
      ORDER BY i.updated_at DESC LIMIT 200`, [pattern, pattern, pattern, pattern, pattern, pattern]);
    const values: StudyItem[] = [];
    try { if (result.goToFirstRow()) do {
      values.push({ id: result.getString(0), kind: result.getString(1), subject: result.getString(2),
        state: result.getLong(3), difficulty: result.getDouble(4), stabilityDays: result.getDouble(5),
        dueAt: result.getLong(6), lastReviewedAt: result.getLong(7), repetitions: result.getLong(8),
        lapses: result.getLong(9), masteryLevel: result.getLong(10), fluentStreak: result.getLong(11),
        prompt: result.getString(12), answer: result.getString(13), templateType: result.getString(14),
        hints: this.strings(result.getString(15)), answerPoints: this.strings(result.getString(16)),
        profile: { archetype: result.getString(17), knowledgePoint: result.getString(18),
          sourceType: result.getString(19), sourceTitle: result.getString(20), sourceChapter: result.getString(21),
          sourceLocator: result.getString(22), mechanism: result.getString(23), conditions: result.getString(24),
          contrast: result.getString(25), example: result.getString(26), commonTrap: result.getString(27),
          transferPrompt: result.getString(28), mnemonic: result.getString(29), firstAttempt: result.getString(30),
          errorTrigger: result.getString(31), generalMethod: result.getString(32), verification: result.getString(33),
          sourceYear: result.getLong(35) > 0 ? result.getLong(35) : undefined,
          targetSeconds: result.getLong(36) > 0 ? result.getLong(36) : undefined,
          structuredPayload: result.getString(37) },
        tags: this.strings(result.getString(34)) });
    } while (result.goToNextRow()); } finally { result.close(); }
    return values;
  }

  async nextForReview(): Promise<StudyItem | undefined> {
    const values = await this.items(); const now = this.now();
    return values.filter(item => item.state === 0 || item.dueAt <= now)
      .sort((left, right) => left.state === right.state ? left.dueAt - right.dueAt : right.state - left.state)[0];
  }

  async createMemoryCard(prompt: string, answer: string): Promise<string> {
    return this.createScientificMemoryCard({ templateType: 'qa', archetype: 'qa',
      subject: 'operating_systems', knowledgePoint: '未分类', prompt, answer, hints: [], answerPoints: [answer],
      mechanism: '', conditions: '', contrast: '', example: '', commonTrap: '', transferPrompt: '',
      mnemonic: '', structuredPayload: '{}', sourceType: 'notes', sourceTitle: '', sourceChapter: '',
      sourceLocator: '', tags: [] });
  }

  async createScientificMemoryCard(draft: MemoryCardDraft): Promise<string> {
    const resolvedAnswer = draft.answer.trim().length > 0 ? draft.answer.trim() : draft.answerPoints.join('\n');
    if (draft.prompt.trim().length === 0 || resolvedAnswer.length === 0)
      throw new Error('回忆问题和核心答案不能为空');
    if (draft.knowledgePoint.trim().length === 0) throw new Error('考点 / 知识点不能为空');
    if (draft.answerPoints.length === 0) throw new Error('至少需要一个评分要点');
    if (['scale_mapping', 'enumeration', 'process'].includes(draft.archetype) && draft.answerPoints.length < 2)
      throw new Error('该知识形式至少需要两个评分要点');
    if (draft.archetype === 'formula_rule' && draft.conditions.trim().length === 0)
      throw new Error('公式规则卡需要适用条件与边界');
    const payload = JSON.parse(draft.structuredPayload || '{}') as Object;
    if (payload === null || typeof payload !== 'object') throw new Error('结构化字段格式无效');
    const store = await this.open(); const id = util.generateRandomUUID(true); const now = this.now();
    store.beginTransaction();
    try {
      await store.executeSql(`INSERT INTO study_item (id, kind, subject, created_at, updated_at)
        VALUES (?, 'memory_card', ?, ?, ?)`, [id, draft.subject, now, now]);
      await store.executeSql(`INSERT INTO card_profile_v5 (study_item_id, archetype, knowledge_point,
        source_type, source_title, source_chapter, source_locator, source_year, mechanism_markdown,
        conditions_markdown, contrast_markdown, example_markdown, common_trap_markdown,
        transfer_prompt_markdown, mnemonic, structured_payload_json, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [id, draft.archetype,
        draft.knowledgePoint.trim(), draft.sourceType, draft.sourceTitle.trim(), draft.sourceChapter.trim(),
        draft.sourceLocator.trim(), draft.sourceYear ?? null, draft.mechanism.trim(), draft.conditions.trim(),
        draft.contrast.trim(), draft.example.trim(), draft.commonTrap.trim(), draft.transferPrompt.trim(),
        draft.mnemonic.trim(), draft.structuredPayload || '{}', now, now]);
      await store.executeSql(`INSERT INTO memory_card (study_item_id, template_type, prompt_markdown,
        answer_markdown, hints_json, answer_points_json) VALUES (?, ?, ?, ?, ?, ?)`,
        [id, draft.templateType, draft.prompt.trim(), resolvedAnswer, JSON.stringify(draft.hints),
          JSON.stringify(draft.answerPoints)]);
      await this.replaceTagsInTransaction(store, id, draft.tags, now);
      store.commit(); return id;
    } catch (error) { store.rollBack(); throw error; }
  }

  async createMathError(draft: MathErrorDraft): Promise<string> {
    const sourceTypes = ['textbook', 'course', 'past_exam', 'practice', 'notes', 'other'];
    const reasons = ['concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'];
    if (draft.knowledgePoint.trim().length === 0) throw new Error('考点 / 知识点不能为空');
    if (draft.prompt.trim().length === 0) throw new Error('错题题面不能为空');
    if (!sourceTypes.includes(draft.sourceType) ||
      (draft.sourceYear !== undefined && (draft.sourceYear < 1900 || draft.sourceYear > 2200)) ||
      (draft.errorReason !== undefined && !reasons.includes(draft.errorReason)) ||
      (draft.targetSeconds !== undefined && (draft.targetSeconds < 10 || draft.targetSeconds > 7200)))
      throw new Error('错题来源、年份、错因或目标用时无效');
    const store = await this.open(); const id = util.generateRandomUUID(true); const now = this.now();
    store.beginTransaction();
    try {
      await store.executeSql(`INSERT INTO study_item (id, kind, subject, created_at, updated_at)
        VALUES (?, 'math_problem', 'math', ?, ?)`, [id, now, now]);
      await store.executeSql(`INSERT INTO card_profile_v5 (study_item_id, archetype, knowledge_point,
        source_type, source_title, source_chapter, source_locator, source_year, first_attempt_markdown,
        error_trigger_markdown, general_method_markdown, verification_markdown, transfer_prompt_markdown,
        target_seconds, structured_payload_json, created_at, updated_at)
        VALUES (?, 'math_error', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?)`, [id,
        draft.knowledgePoint.trim(), draft.sourceType, draft.sourceTitle.trim(), draft.sourceChapter.trim(),
        draft.sourceLocator.trim(), draft.sourceYear ?? null, draft.firstAttempt.trim(), draft.errorTrigger.trim(),
        draft.generalMethod.trim(), draft.verification.trim(), draft.transferPrompt.trim(),
        draft.targetSeconds ?? null, now, now]);
      await store.executeSql(`INSERT INTO math_problem (study_item_id, source_name, source_page, source_year,
        prompt_markdown, solution_markdown, wrong_step_markdown, key_hint_markdown, default_error_reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, [id, draft.sourceTitle.trim(), draft.sourceLocator.trim(),
        draft.sourceYear ?? null, draft.prompt.trim(), draft.solution.trim(), draft.firstAttempt.trim(),
        draft.keyHint.trim(), draft.errorReason ?? null]);
      await this.replaceTagsInTransaction(store, id, draft.tags, now);
      store.commit(); return id;
    } catch (error) { store.rollBack(); throw error; }
  }

  async review(item: StudyItem, feedback: number, durationSeconds: number,
    evidence?: ReviewEvidence): Promise<number> {
    const store = await this.open(); const reviewedAt = this.now();
    const proof: ReviewEvidence = evidence ?? { hintLevel: 0, answerRevealed: false,
      confidence: 3, reflection: '' };
    if (proof.hintLevel < 0 || proof.hintLevel > 9 || proof.confidence < 1 || proof.confidence > 5 ||
      (proof.pointHits === undefined) !== (proof.pointCount === undefined) ||
      (proof.pointCount !== undefined && (proof.pointCount <= 0 || (proof.pointHits ?? 0) > proof.pointCount)))
      throw new Error('复习证据无效');
    let dueAt = 0; let appliedFeedback = feedback; let nextState = item.state;
    let difficulty = item.difficulty; let stability = item.stabilityDays;
    let mastery = item.masteryLevel; let streak = item.fluentStreak;
    let repetitions = item.repetitions; let lapses = item.lapses; let parameterVersion = 2;
    if (item.kind === 'memory_card') {
      const value = reviewfault.reviewMemoryV3({ state: item.state, difficulty: item.difficulty,
        stabilityDays: item.stabilityDays, dueAt: item.dueAt, lastReviewedAt: item.lastReviewedAt,
        repetitions: item.repetitions, lapses: item.lapses, rating: feedback, preset: 1,
        reviewedAt, historyCount: item.repetitions, calibrationImprovement: 0, consecutiveLapses: 0 });
      dueAt = value.dueAt; nextState = value.state; difficulty = value.difficulty; stability = value.stabilityDays;
      repetitions = value.repetitions; lapses = value.lapses; parameterVersion = value.parameterVersion;
    } else {
      const value = reviewfault.reviewMathV3({ masteryLevel: item.masteryLevel, fluentStreak: item.fluentStreak,
        dueAt: item.dueAt, lastReviewedAt: item.lastReviewedAt, repetitions: item.repetitions,
        feedback, errorReason: 0, hintRevealed: proof.hintLevel > 0 || proof.answerRevealed ? 1 : 0,
        intensity: 1, reviewedAt,
        durationSeconds, durationQuality: durationSeconds < 5 ? 2 : 1, consecutiveFailures: 0 });
      dueAt = value.dueAt; appliedFeedback = value.appliedFeedback; mastery = value.masteryLevel;
      streak = value.fluentStreak; repetitions = value.repetitions; stability = value.scheduledDays;
      difficulty = mastery + 1; lapses += appliedFeedback <= 1 ? 1 : 0;
      parameterVersion = value.parameterVersion; nextState = value.repetitions === 0 ? 0 : 2;
    }
    store.beginTransaction();
    try {
      await store.executeSql(`UPDATE study_item SET scheduler_state = ?, difficulty = ?,
        stability_days = ?, due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?,
        updated_at = ? WHERE id = ?`,
        [nextState, difficulty, stability, dueAt, reviewedAt, repetitions, lapses, reviewedAt, item.id]);
      await store.executeSql(`UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?,
        repetitions = ?, needs_history_replay = 0, active_algorithm_version = 3,
        active_parameter_version = ?, updated_at = ? WHERE study_item_id = ?`,
        [dueAt, reviewedAt, repetitions, parameterVersion, reviewedAt, item.id]);
      if (item.kind === 'memory_card') await store.executeSql(`UPDATE memory_schedule_state SET
        state = ?, difficulty = ?, stability_days = ?, lapses = ? WHERE study_item_id = ?`,
        [nextState, difficulty, stability, lapses, item.id]);
      else await store.executeSql(`UPDATE math_schedule_state SET mastery_level = ?, fluent_streak = ?
        WHERE study_item_id = ?`, [mastery, streak, item.id]);
      const actionId = util.generateRandomUUID(true); const identity = await this.counter(store);
      const algorithm = item.kind === 'memory_card' ? 'memory_fsrs_6' : 'math_mastery_ladder';
      await store.executeSql(`INSERT INTO review_action_v4 (action_id, study_item_id, algorithm, feedback,
        reviewed_at, duration_seconds, hint_revealed, device_id, device_counter, causal_cursor,
        source_generation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 4, ?)`,
        [actionId, item.id, algorithm, appliedFeedback, reviewedAt, durationSeconds,
          proof.hintLevel > 0 || proof.answerRevealed ? 1 : 0,
          identity.deviceId, identity.counter, identity.cursor, reviewedAt]);
      await this.insertOutbox(store, identity, 'reviewAction', actionId, 'create', { studyItemId: item.id,
        algorithm, feedback: appliedFeedback, reviewedAt, durationSeconds,
        hintRevealed: proof.hintLevel > 0 || proof.answerRevealed }, reviewedAt);
      await store.executeSql(`UPDATE schedule_cache_v4 SET due_at = ?, replayed_action_count = ?,
        dirty = 0, rebuilt_at = ? WHERE study_item_id = ?`, [dueAt, item.repetitions + 1, reviewedAt, item.id]);
      const taskQuery = await store.querySql(`SELECT id, task_type, repetitions, consecutive_failures
        FROM learning_task_v5 WHERE source_study_item_id = ? AND task_state IN ('active', 'legacy')
        AND dependency_ready = 1 ORDER BY due_at, id LIMIT 1`, [item.id]);
      if (taskQuery.goToFirstRow()) {
        const taskId = taskQuery.getString(0); const taskType = taskQuery.getString(1);
        const taskRepetitions = taskQuery.getLong(2); const failures = taskQuery.getLong(3);
        const correct = item.kind === 'memory_card' ? appliedFeedback >= 3 : appliedFeedback >= 2;
        await store.executeSql(`UPDATE learning_task_v5 SET task_state = 'active', due_at = ?,
          last_reviewed_at = ?, repetitions = ?, consecutive_failures = ?, updated_at = ? WHERE id = ?`,
          [dueAt, reviewedAt, taskRepetitions + 1, correct ? 0 : failures + 1, reviewedAt, taskId]);
        const device = await store.querySql('SELECT device_id, next_counter FROM local_device WHERE singleton = 1');
        if (device.goToFirstRow()) await store.executeSql(`INSERT INTO learning_evidence_v5
          (evidence_id, learning_task_id, task_type, reviewed_at, correct, point_hits, point_count,
          hint_level, answer_revealed, duration_seconds, duration_reliable, confidence,
          reflection_markdown, device_id, device_counter, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [util.generateRandomUUID(true),
          taskId, taskType, reviewedAt, correct ? 1 : 0, proof.pointHits ?? null, proof.pointCount ?? null,
          proof.hintLevel, proof.answerRevealed ? 1 : 0, durationSeconds,
          durationSeconds >= 5 && durationSeconds <= 3600 ? 1 : 0, proof.confidence,
          proof.reflection.trim(), device.getString(0), device.getLong(1), reviewedAt]);
        device.close();
      }
      taskQuery.close();
      store.commit(); return dueAt;
    } catch (error) { store.rollBack(); throw error; }
  }

  async bindAccount(accountId: string, workspaceId: string): Promise<void> {
    const store = await this.open(); const current = await this.syncIdentity();
    if (current.workspaceId.length > 0 && current.workspaceId !== workspaceId)
      throw new Error('此本地数据目录已绑定其他账号，请先导出备份');
    await store.executeSql('UPDATE local_device SET account_id = ?, workspace_id = ? WHERE singleton = 1',
      [accountId, workspaceId]);
    await store.executeSql(`INSERT OR IGNORE INTO sync_cursor (workspace_id, server_seq, updated_at)
      VALUES (?, 0, ?)`, [workspaceId, this.now()]);
  }

  async syncIdentity(): Promise<SyncIdentity> {
    const store = await this.open(); const result = await store.querySql(`SELECT d.device_id,
      COALESCE(d.workspace_id, ''), COALESCE((SELECT server_seq FROM sync_cursor
      WHERE workspace_id = d.workspace_id), 0), (SELECT COUNT(*) FROM sync_outbox)
      FROM local_device d WHERE singleton = 1`);
    try { result.goToFirstRow(); return { deviceId: result.getString(0), workspaceId: result.getString(1),
      cursor: result.getLong(2), pendingCount: result.getLong(3) }; } finally { result.close(); }
  }

  async pendingOperations(): Promise<LocalSyncOperation[]> {
    const store = await this.open(); const result = await store.querySql(`SELECT operation_id, device_id,
      device_counter, base_cursor, base_revision, entity_type, entity_id, action,
      changed_fields_json, occurred_at FROM sync_outbox ORDER BY device_counter LIMIT 200`);
    const values: LocalSyncOperation[] = [];
    try { if (result.goToFirstRow()) do { values.push({ operationId: result.getString(0),
      deviceId: result.getString(1), deviceCounter: result.getLong(2), baseCursor: result.getLong(3),
      baseRevision: result.getLong(4), entityType: result.getString(5), entityId: result.getString(6),
      action: result.getString(7), changedFields: JSON.parse(result.getString(8)) as Record<string, Object>,
      occurredAt: result.getLong(9) }); } while (result.goToNextRow()); } finally { result.close(); }
    return values;
  }

  async acknowledge(operationIds: string[]): Promise<void> {
    const store = await this.open(); store.beginTransaction();
    try { for (const id of operationIds) await store.executeSql('DELETE FROM sync_outbox WHERE operation_id = ?', [id]);
      store.commit(); } catch (error) { store.rollBack(); throw error; }
  }

  async applyPulled(workspaceId: string, operations: PulledOperation[], cursor: number): Promise<void> {
    const store = await this.open(); store.beginTransaction();
    try {
      for (const operation of operations.sort((left, right) => left.serverSeq - right.serverSeq)) {
        const counterQuery = await store.querySql('SELECT next_counter FROM local_device WHERE singleton = 1');
        let remoteApplyCounterStart = 0;
        try { counterQuery.goToFirstRow(); remoteApplyCounterStart = counterQuery.getLong(0); }
        finally { counterQuery.close(); }
        if (operation.entityType === 'studyItem') await this.applyStudyItem(store, operation);
        else if (operation.entityType === 'memoryCard') await this.applyMemoryCard(store, operation);
        else if (operation.entityType === 'mathProblem') await this.applyMathProblem(store, operation);
        else if (operation.entityType === 'cardProfile') await this.applyCardProfile(store, operation);
        else if (operation.entityType === 'tag') await this.applyTag(store, operation);
        else if (operation.entityType === 'relation') await this.applyRelation(store, operation);
        else if (operation.entityType === 'learningPreferences') await this.applyLearningPreferences(store, operation);
        else if (operation.entityType === 'reviewAction') await this.applyReviewAction(store, operation);
        else if (operation.entityType === 'learningEvidence') await this.applyLearningEvidence(store, operation);
        await store.executeSql(`DELETE FROM sync_outbox WHERE device_id =
          (SELECT device_id FROM local_device WHERE singleton = 1) AND device_counter >= ?`,
          [remoteApplyCounterStart]);
        await store.executeSql(`DELETE FROM relation_operation WHERE device_id =
          (SELECT device_id FROM local_device WHERE singleton = 1) AND device_counter >= ?`,
          [remoteApplyCounterStart]);
        await store.executeSql(`INSERT INTO sync_revision (entity_type, entity_id, revision, server_seq, deleted)
          VALUES (?, ?, 1, ?, ?) ON CONFLICT(entity_type, entity_id) DO UPDATE SET
          revision = revision + 1, server_seq = MAX(server_seq, excluded.server_seq), deleted = excluded.deleted`,
          [operation.entityType, operation.entityId, operation.serverSeq, operation.action === 'delete' ? 1 : 0]);
      }
      await store.executeSql(`INSERT INTO sync_cursor (workspace_id, server_seq, updated_at) VALUES (?, ?, ?)
        ON CONFLICT(workspace_id) DO UPDATE SET server_seq = MAX(server_seq, excluded.server_seq),
        updated_at = excluded.updated_at`, [workspaceId, cursor, this.now()]);
      store.commit();
    } catch (error) { store.rollBack(); throw error; }
    await this.rebuildDirtySchedules();
  }

  private async applyStudyItem(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields; const check = await store.querySql('SELECT COUNT(*) FROM study_item WHERE id = ?', [operation.entityId]);
    let exists = false; try { exists = check.goToFirstRow() && check.getLong(0) > 0; } finally { check.close(); }
    if (!exists) {
      const kind = String(fields['kind'] ?? 'memory_card'); const now = Number(fields['updatedAt'] ?? operation.occurredAt);
      await store.executeSql(`INSERT INTO study_item (id, kind, subject, created_at, updated_at, deleted_at)
        VALUES (?, ?, ?, ?, ?, ?)`, [operation.entityId, kind, String(fields['subject'] ?? 'operating_systems'),
        Number(fields['createdAt'] ?? operation.occurredAt), now, operation.action === 'delete' ? operation.occurredAt : null]);
      if (kind === 'memory_card') await store.executeSql(`INSERT INTO memory_card (study_item_id,
        template_type, prompt_markdown, answer_markdown, hints_json, answer_points_json)
        VALUES (?, ?, ?, ?, ?, ?)`, [operation.entityId, String(fields['templateType'] ?? 'qa'),
        String(fields['prompt'] ?? ''), String(fields['answer'] ?? ''), JSON.stringify(fields['hints'] ?? []),
        JSON.stringify(fields['answerPoints'] ?? [])]);
      else await store.executeSql(`INSERT INTO math_problem (study_item_id, source_name, solution_markdown,
        wrong_step_markdown, key_hint_markdown) VALUES (?, ?, ?, ?, ?)`, [operation.entityId,
        String(fields['sourceName'] ?? ''), String(fields['solution'] ?? ''), String(fields['wrongStep'] ?? ''),
        String(fields['keyHint'] ?? '')]);
    } else {
      if (fields['subject'] !== undefined) await store.executeSql(
        'UPDATE study_item SET subject = ?, updated_at = ? WHERE id = ?',
        [String(fields['subject']), Number(fields['updatedAt'] ?? operation.occurredAt), operation.entityId]);
      if (operation.action === 'delete' || operation.action === 'restore') await store.executeSql(
        'UPDATE study_item SET deleted_at = ?, updated_at = ? WHERE id = ?',
        [operation.action === 'delete' ? operation.occurredAt : null, operation.occurredAt, operation.entityId]);
      const kindQuery = await store.querySql('SELECT kind FROM study_item WHERE id = ?', [operation.entityId]);
      let kind = 'memory_card';
      try { kindQuery.goToFirstRow(); kind = kindQuery.getString(0); } finally { kindQuery.close(); }
      if (kind === 'memory_card') {
        if (fields['templateType'] !== undefined) await store.executeSql(
          'UPDATE memory_card SET template_type = ? WHERE study_item_id = ?',
          [String(fields['templateType']), operation.entityId]);
        if (fields['prompt'] !== undefined) await store.executeSql(
          'UPDATE memory_card SET prompt_markdown = ? WHERE study_item_id = ?',
          [String(fields['prompt']), operation.entityId]);
        if (fields['answer'] !== undefined) await store.executeSql(
          'UPDATE memory_card SET answer_markdown = ? WHERE study_item_id = ?',
          [String(fields['answer']), operation.entityId]);
        if (fields['hints'] !== undefined) await store.executeSql(
          'UPDATE memory_card SET hints_json = ? WHERE study_item_id = ?',
          [JSON.stringify(fields['hints']), operation.entityId]);
        if (fields['answerPoints'] !== undefined) await store.executeSql(
          'UPDATE memory_card SET answer_points_json = ? WHERE study_item_id = ?',
          [JSON.stringify(fields['answerPoints']), operation.entityId]);
      } else {
        if (fields['sourceName'] !== undefined) await store.executeSql(
          'UPDATE math_problem SET source_name = ? WHERE study_item_id = ?',
          [String(fields['sourceName']), operation.entityId]);
        if (fields['solution'] !== undefined) await store.executeSql(
          'UPDATE math_problem SET solution_markdown = ? WHERE study_item_id = ?',
          [String(fields['solution']), operation.entityId]);
        if (fields['wrongStep'] !== undefined) await store.executeSql(
          'UPDATE math_problem SET wrong_step_markdown = ? WHERE study_item_id = ?',
          [String(fields['wrongStep']), operation.entityId]);
        if (fields['keyHint'] !== undefined) await store.executeSql(
          'UPDATE math_problem SET key_hint_markdown = ? WHERE study_item_id = ?',
          [String(fields['keyHint']), operation.entityId]);
      }
    }
  }

  private async applyReviewAction(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields; const itemId = String(fields['studyItemId']);
    const check = await store.querySql('SELECT COUNT(*) FROM study_item WHERE id = ?', [itemId]);
    let exists = false; try { exists = check.goToFirstRow() && check.getLong(0) > 0; } finally { check.close(); }
    if (!exists) return;
    await store.executeSql(`INSERT OR IGNORE INTO review_action_v4 (action_id, study_item_id, algorithm,
      feedback, reviewed_at, duration_seconds, error_reason, hint_revealed, device_id, device_counter,
      causal_cursor, source_generation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 4, ?)`,
      [operation.entityId, itemId, String(fields['algorithm']), Number(fields['feedback']),
        Number(fields['reviewedAt']), Number(fields['durationSeconds'] ?? 0), fields['errorReason'] ?? null,
        Boolean(fields['hintRevealed'] ?? false) ? 1 : 0, operation.deviceId, operation.deviceCounter,
        operation.serverSeq, operation.occurredAt]);
    await store.executeSql('UPDATE schedule_cache_v4 SET dirty = 1 WHERE study_item_id = ?', [itemId]);
  }

  private async applyLearningEvidence(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields; const taskId = String(fields['taskId'] ?? '');
    if (taskId.length === 0) return;
    await store.executeSql(`INSERT OR IGNORE INTO learning_evidence_v5 (evidence_id, learning_task_id,
      task_type, reviewed_at, correct, error_mask, point_hits, point_count, hint_level, answer_revealed,
      duration_seconds, duration_reliable, confidence, reflection_markdown, artifact_id, device_id,
      device_counter, causal_cursor, created_at)
      SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      WHERE EXISTS (SELECT 1 FROM learning_task_v5 WHERE id = ?)`, [operation.entityId, taskId,
      String(fields['taskType'] ?? 'memory_recall'), Number(fields['reviewedAt'] ?? operation.occurredAt),
      Number(fields['correct'] ?? 0), Number(fields['errorMask'] ?? 0), fields['pointHits'] ?? null,
      fields['pointCount'] ?? null, Number(fields['hintLevel'] ?? 0), Number(fields['answerRevealed'] ?? 0),
      fields['durationSeconds'] ?? null, Number(fields['durationReliable'] ?? 1), fields['confidence'] ?? null,
      String(fields['reflection'] ?? ''), fields['artifactId'] ?? null, operation.deviceId,
      operation.deviceCounter, operation.serverSeq, operation.occurredAt, taskId]);
  }

  private async applyCardProfile(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields;
    const check = await store.querySql('SELECT COUNT(*) FROM card_profile_v5 WHERE study_item_id = ?',
      [operation.entityId]);
    let exists = false;
    try { exists = check.goToFirstRow() && check.getLong(0) > 0; } finally { check.close(); }
    if (!exists) await store.executeSql(`INSERT INTO card_profile_v5 (study_item_id, archetype,
      knowledge_point, source_type, source_title, source_chapter, source_locator, source_year,
      mechanism_markdown, conditions_markdown, contrast_markdown, example_markdown,
      common_trap_markdown, transfer_prompt_markdown, mnemonic, first_attempt_markdown,
      error_trigger_markdown, general_method_markdown, verification_markdown, target_seconds,
      structured_payload_json, created_at, updated_at)
      SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      WHERE EXISTS (SELECT 1 FROM study_item WHERE id = ?)`, [operation.entityId,
      String(fields['archetype'] ?? 'qa'), String(fields['knowledgePoint'] ?? ''),
      String(fields['sourceType'] ?? 'notes'), String(fields['sourceTitle'] ?? ''),
      String(fields['sourceChapter'] ?? ''), String(fields['sourceLocator'] ?? ''), fields['sourceYear'] ?? null,
      String(fields['mechanism'] ?? ''), String(fields['conditions'] ?? ''), String(fields['contrast'] ?? ''),
      String(fields['example'] ?? ''), String(fields['commonTrap'] ?? ''), String(fields['transferPrompt'] ?? ''),
      String(fields['mnemonic'] ?? ''), String(fields['firstAttempt'] ?? ''),
      String(fields['errorTrigger'] ?? ''), String(fields['generalMethod'] ?? ''),
      String(fields['verification'] ?? ''), fields['targetSeconds'] ?? null,
      JSON.stringify(fields['structuredPayload'] ?? {}), operation.occurredAt, operation.occurredAt,
      operation.entityId]);
    const mappings: Array<{wire: string, column: string}> = [
      { wire: 'archetype', column: 'archetype' }, { wire: 'knowledgePoint', column: 'knowledge_point' },
      { wire: 'sourceType', column: 'source_type' }, { wire: 'sourceTitle', column: 'source_title' },
      { wire: 'sourceChapter', column: 'source_chapter' }, { wire: 'sourceLocator', column: 'source_locator' },
      { wire: 'sourceYear', column: 'source_year' }, { wire: 'mechanism', column: 'mechanism_markdown' },
      { wire: 'conditions', column: 'conditions_markdown' }, { wire: 'contrast', column: 'contrast_markdown' },
      { wire: 'example', column: 'example_markdown' }, { wire: 'commonTrap', column: 'common_trap_markdown' },
      { wire: 'transferPrompt', column: 'transfer_prompt_markdown' }, { wire: 'mnemonic', column: 'mnemonic' },
      { wire: 'firstAttempt', column: 'first_attempt_markdown' },
      { wire: 'errorTrigger', column: 'error_trigger_markdown' },
      { wire: 'generalMethod', column: 'general_method_markdown' },
      { wire: 'verification', column: 'verification_markdown' },
      { wire: 'targetSeconds', column: 'target_seconds' },
    ];
    for (const mapping of mappings) if (fields[mapping.wire] !== undefined) await store.executeSql(
      `UPDATE card_profile_v5 SET ${mapping.column} = ?, updated_at = ? WHERE study_item_id = ?`,
      [fields[mapping.wire], operation.occurredAt, operation.entityId]);
    if (fields['structuredPayload'] !== undefined) await store.executeSql(
      'UPDATE card_profile_v5 SET structured_payload_json = ?, updated_at = ? WHERE study_item_id = ?',
      [JSON.stringify(fields['structuredPayload']), operation.occurredAt, operation.entityId]);
  }

  private async applyMemoryCard(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields;
    if (fields['templateType'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET template_type = ? WHERE study_item_id = ?',
      [String(fields['templateType']), operation.entityId]);
    if (fields['promptMarkdown'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET prompt_markdown = ? WHERE study_item_id = ?',
      [String(fields['promptMarkdown']), operation.entityId]);
    if (fields['answerMarkdown'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET answer_markdown = ? WHERE study_item_id = ?',
      [String(fields['answerMarkdown']), operation.entityId]);
    if (fields['hints'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET hints_json = ? WHERE study_item_id = ?',
      [JSON.stringify(fields['hints']), operation.entityId]);
    if (fields['answerPoints'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET answer_points_json = ? WHERE study_item_id = ?',
      [JSON.stringify(fields['answerPoints']), operation.entityId]);
    if (fields['occlusions'] !== undefined) await store.executeSql(
      'UPDATE memory_card SET occlusions_json = ? WHERE study_item_id = ?',
      [JSON.stringify(fields['occlusions']), operation.entityId]);
  }

  private async applyMathProblem(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields;
    const mappings: Array<{wire: string, column: string}> = [
      { wire: 'sourceName', column: 'source_name' }, { wire: 'promptMarkdown', column: 'prompt_markdown' },
      { wire: 'solutionMarkdown', column: 'solution_markdown' },
      { wire: 'wrongStepMarkdown', column: 'wrong_step_markdown' },
      { wire: 'keyHintMarkdown', column: 'key_hint_markdown' },
      { wire: 'defaultErrorReason', column: 'default_error_reason' },
    ];
    for (const mapping of mappings) if (fields[mapping.wire] !== undefined) await store.executeSql(
      `UPDATE math_problem SET ${mapping.column} = ? WHERE study_item_id = ?`,
      [String(fields[mapping.wire]), operation.entityId]);
  }

  private async applyTag(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const name = String(operation.changedFields['name'] ?? '').trim(); if (name.length === 0) return;
    await store.executeSql(`INSERT OR IGNORE INTO tag (id, name, created_at, updated_at, deleted_at)
      VALUES (?, ?, ?, ?, ?)`, [operation.entityId, name, operation.occurredAt, operation.occurredAt,
      operation.action === 'delete' ? operation.occurredAt : null]);
    await store.executeSql('UPDATE tag SET name = ?, updated_at = ?, deleted_at = ? WHERE id = ?',
      [name, operation.occurredAt, operation.action === 'delete' ? operation.occurredAt : null,
        operation.entityId]);
  }

  private async applyRelation(store: relationalStore.RdbStore, operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields;
    if (String(fields['relationType'] ?? '') !== 'study_item_tag') return;
    const source = String(fields['sourceId'] ?? ''); const target = String(fields['targetId'] ?? '');
    if (source.length === 0 || target.length === 0 || !['add', 'remove'].includes(operation.action)) return;
    await store.executeSql(`INSERT OR IGNORE INTO relation_operation (operation_id, relation_type,
      source_id, target_id, action, device_id, device_counter, observed_adds_json, occurred_at)
      VALUES (?, 'study_item_tag', ?, ?, ?, ?, ?, ?, ?)`, [operation.operationId, source, target,
      operation.action, operation.deviceId, operation.deviceCounter,
      JSON.stringify(fields['observedAdds'] ?? []), operation.occurredAt]);
    const query = await store.querySql(`SELECT EXISTS (SELECT 1 FROM relation_operation add_op
      WHERE add_op.relation_type = 'study_item_tag' AND add_op.source_id = ? AND add_op.target_id = ?
        AND add_op.action = 'add' AND NOT EXISTS (SELECT 1 FROM relation_operation remove_op,
          json_each(remove_op.observed_adds_json) observed
          WHERE remove_op.relation_type = add_op.relation_type
            AND remove_op.source_id = add_op.source_id AND remove_op.target_id = add_op.target_id
            AND remove_op.action = 'remove' AND observed.value = add_op.operation_id))`, [source, target]);
    let active = false; try { active = query.goToFirstRow() && query.getLong(0) !== 0; } finally { query.close(); }
    if (active) await store.executeSql(`INSERT OR IGNORE INTO study_item_tag
      (study_item_id, tag_id) SELECT ?, ? WHERE EXISTS (SELECT 1 FROM study_item WHERE id = ?)
      AND EXISTS (SELECT 1 FROM tag WHERE id = ?)`, [source, target, source, target]);
    else await store.executeSql(
      'DELETE FROM study_item_tag WHERE study_item_id = ? AND tag_id = ?', [source, target]);
  }

  private async applyLearningPreferences(store: relationalStore.RdbStore,
    operation: PulledOperation): Promise<void> {
    const fields = operation.changedFields;
    const mappings: Array<{wire: string, column: string}> = [
      { wire: 'dailyNewMemoryLimit', column: 'daily_new_memory_limit' },
      { wire: 'sessionMinutes', column: 'session_minutes' },
      { wire: 'memoryPreset', column: 'memory_preset' },
      { wire: 'mathIntensity', column: 'math_intensity' },
      { wire: 'schedulerGeneration', column: 'scheduler_generation' },
    ];
    for (const mapping of mappings) if (fields[mapping.wire] !== undefined) await store.executeSql(
      `UPDATE learning_preferences SET ${mapping.column} = ?, updated_at = ? WHERE singleton = 1`,
      [fields[mapping.wire], operation.occurredAt]);
  }

  private async rebuildDirtySchedules(): Promise<void> {
    const store = await this.open(); const query = await store.querySql(`SELECT c.study_item_id, i.kind
      FROM schedule_cache_v4 c JOIN study_item i ON i.id = c.study_item_id WHERE c.dirty = 1`);
    const dirty: Array<{id: string, kind: string}> = [];
    try { if (query.goToFirstRow()) do { dirty.push({ id: query.getString(0), kind: query.getString(1) }); }
      while (query.goToNextRow()); } finally { query.close(); }
    store.beginTransaction();
    try { for (const item of dirty) {
      const facts = await this.reviewFacts(store, item.id); const order = reviewfault.canonicalOrderV4(facts);
      let dueAt = 0; let lastReviewedAt = 0;
      if (item.kind === 'memory_card') {
        let state = 0; let difficulty = 0; let stability = 0; let repetitions = 0; let lapses = 0;
        let consecutiveLapses = 0;
        order.forEach((index: number, history: number) => { const fact = facts[index];
          const effective = Math.max(fact.reviewedAt, lastReviewedAt);
          const value = reviewfault.reviewMemoryV3({ state, difficulty, stabilityDays: stability, dueAt,
            lastReviewedAt, repetitions, lapses, rating: fact.feedback, preset: 1, reviewedAt: effective,
            historyCount: history, calibrationImprovement: 0, consecutiveLapses });
          state = value.state; difficulty = value.difficulty; stability = value.stabilityDays;
          dueAt = value.dueAt; lastReviewedAt = effective; repetitions = value.repetitions; lapses = value.lapses;
          consecutiveLapses = fact.feedback === 1 ? consecutiveLapses + 1 : 0;
        });
        await store.executeSql(`UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?,
          repetitions = ?, updated_at = ? WHERE study_item_id = ?`,
          [dueAt, lastReviewedAt, repetitions, this.now(), item.id]);
        await store.executeSql(`UPDATE memory_schedule_state SET state = ?, difficulty = ?,
          stability_days = ?, lapses = ? WHERE study_item_id = ?`, [state, difficulty, stability, lapses, item.id]);
        await store.executeSql(`UPDATE study_item SET scheduler_state = ?, difficulty = ?,
          stability_days = ?, due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?,
          updated_at = ? WHERE id = ?`, [state, difficulty, stability, dueAt, lastReviewedAt,
          repetitions, lapses, this.now(), item.id]);
      } else {
        let mastery = 0; let streak = 0; let repetitions = 0; let failures = 0;
        let lapses = 0; let scheduledDays = 0;
        order.forEach((index: number) => { const fact = facts[index]; const effective = Math.max(fact.reviewedAt, lastReviewedAt);
          const value = reviewfault.reviewMathV3({ masteryLevel: mastery, fluentStreak: streak, dueAt,
            lastReviewedAt, repetitions, feedback: fact.feedback, errorReason: this.errorReason(fact.errorReason),
            hintRevealed: fact.hintRevealed ? 1 : 0, intensity: 1, reviewedAt: effective,
            durationSeconds: fact.durationSeconds, durationQuality: 0, consecutiveFailures: failures });
          mastery = value.masteryLevel; streak = value.fluentStreak; dueAt = value.dueAt;
          lastReviewedAt = effective; repetitions = value.repetitions; scheduledDays = value.scheduledDays;
          if (fact.feedback <= 1) lapses++; failures = fact.feedback <= 1 ? failures + 1 : 0;
        });
        await store.executeSql(`UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?,
          repetitions = ?, updated_at = ? WHERE study_item_id = ?`,
          [dueAt, lastReviewedAt, repetitions, this.now(), item.id]);
        await store.executeSql(`UPDATE math_schedule_state SET mastery_level = ?, fluent_streak = ?
          WHERE study_item_id = ?`, [mastery, streak, item.id]);
        await store.executeSql(`UPDATE study_item SET scheduler_state = ?, difficulty = ?,
          stability_days = ?, due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?,
          updated_at = ? WHERE id = ?`, [repetitions === 0 ? 0 : 2,
          repetitions === 0 ? 0 : mastery + 1, scheduledDays, dueAt, lastReviewedAt,
          repetitions, lapses, this.now(), item.id]);
      }
      await store.executeSql(`UPDATE schedule_cache_v4 SET due_at = ?, replayed_action_count = ?,
        dirty = 0, rebuilt_at = ? WHERE study_item_id = ?`, [dueAt, facts.length, this.now(), item.id]);
    } store.commit(); } catch (error) { store.rollBack(); throw error; }
  }

  private async reviewFacts(store: relationalStore.RdbStore, itemId: string): Promise<ReviewFact[]> {
    const result = await store.querySql(`SELECT action_id, device_id, device_counter, causal_cursor,
      feedback, reviewed_at, COALESCE(duration_seconds, 0), COALESCE(error_reason, ''), hint_revealed
      FROM review_action_v4 WHERE study_item_id = ?`, [itemId]); const values: ReviewFact[] = [];
    try { if (result.goToFirstRow()) do { values.push({ actionId: result.getString(0), deviceId: result.getString(1),
      deviceCounter: result.getLong(2), causalCursor: result.getLong(3), feedback: result.getLong(4),
      reviewedAt: result.getLong(5), durationSeconds: result.getLong(6), errorReason: result.getString(7),
      hintRevealed: result.getLong(8) !== 0 }); } while (result.goToNextRow()); } finally { result.close(); }
    return values;
  }
  private errorReason(value: string): number { return ['','concept','approach','calculation','misread',
    'forgotten_fact','timeout','other'].indexOf(value); }

  private strings(value: string): string[] {
    try { const parsed = JSON.parse(value) as string[]; return Array.isArray(parsed) ? parsed : []; }
    catch (_) { return []; }
  }

  private async replaceTagsInTransaction(store: relationalStore.RdbStore, itemId: string,
    names: string[], now: number): Promise<void> {
    await store.executeSql('DELETE FROM study_item_tag WHERE study_item_id = ?', [itemId]);
    const normalized: string[] = [];
    names.map(value => value.trim()).filter(value => value.length > 0).forEach(value => {
      if (!normalized.some(existing => existing.toLowerCase() === value.toLowerCase())) normalized.push(value);
    });
    for (const name of normalized.slice(0, 30)) {
      if (name.length > 60) throw new Error('单个标签不能超过 60 个字符');
      const candidate = util.generateRandomUUID(true);
      await store.executeSql(`INSERT OR IGNORE INTO tag (id, name, created_at, updated_at)
        VALUES (?, ?, ?, ?)`, [candidate, name, now, now]);
      await store.executeSql(`UPDATE tag SET deleted_at = NULL, updated_at = ?
        WHERE name = ? COLLATE NOCASE`, [now, name]);
      const query = await store.querySql('SELECT id FROM tag WHERE name = ? COLLATE NOCASE', [name]);
      let tagId = candidate;
      try { if (query.goToFirstRow()) tagId = query.getString(0); } finally { query.close(); }
      await this.enqueue(store, 'tag', tagId, 'create', { name }, now);
      await store.executeSql('INSERT OR IGNORE INTO study_item_tag (study_item_id, tag_id) VALUES (?, ?)',
        [itemId, tagId]);
    }
  }

  private async enqueue(store: relationalStore.RdbStore, type: string, id: string, action: string,
    fields: Record<string, Object>, occurredAt: number): Promise<void> {
    const identity = await this.counter(store); await this.insertOutbox(store, identity, type, id, action, fields, occurredAt);
  }
  private async counter(store: relationalStore.RdbStore): Promise<{deviceId: string, counter: number, cursor: number}> {
    const result = await store.querySql(`SELECT d.device_id, d.next_counter, COALESCE((SELECT server_seq
      FROM sync_cursor WHERE workspace_id = d.workspace_id), 0) FROM local_device d WHERE singleton = 1`);
    try { result.goToFirstRow(); const value = { deviceId: result.getString(0), counter: result.getLong(1), cursor: result.getLong(2) };
      await store.executeSql('UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1'); return value;
    } finally { result.close(); }
  }
  private async insertOutbox(store: relationalStore.RdbStore, identity: {deviceId: string, counter: number, cursor: number},
    type: string, id: string, action: string, fields: Record<string, Object>, occurredAt: number): Promise<void> {
    await store.executeSql(`INSERT INTO sync_outbox (operation_id, device_id, device_counter, base_cursor,
      base_revision, entity_type, entity_id, action, changed_fields_json, occurred_at)
      VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?)`, [util.generateRandomUUID(true), identity.deviceId,
      identity.counter, identity.cursor, type, id, action, JSON.stringify(fields), occurredAt]);
  }
  private now(): number { return Math.floor(Date.now() / 1000); }

  private async version(store: relationalStore.RdbStore): Promise<number> {
    const result = await store.querySql('PRAGMA user_version');
    try { return result.goToFirstRow() ? result.getLong(0) : 0; } finally { result.close(); }
  }
  private async applyMigration(store: relationalStore.RdbStore, version: number): Promise<void> {
    const names = ['', '001_initial.sql', '002_v0_2.sql', '003_v0_3.sql', '004_v0_4.sql', '005_v0_5.sql'];
    const bytes = await this.context.resourceManager.getRawFileContent(`contracts/${names[version]}`);
    const script = new TextDecoder().decode(bytes); store.beginTransaction();
    try { for (const statement of this.statements(script)) await store.executeSql(statement);
      await store.executeSql(`PRAGMA user_version = ${version}`); store.commit();
    } catch (error) { store.rollBack(); throw error; }
  }
  private statements(script: string): string[] {
    const result: string[] = []; let current = ''; let trigger = false;
    for (const raw of script.split('\n')) { const line = raw.trim();
      if (line.length === 0 || line.startsWith('--') || line.toUpperCase().startsWith('PRAGMA FOREIGN_KEYS') ||
        line.toUpperCase().startsWith('PRAGMA USER_VERSION') || line.toUpperCase() === 'BEGIN IMMEDIATE;' ||
        line.toUpperCase() === 'COMMIT;') continue;
      if (line.toUpperCase().startsWith('CREATE TRIGGER')) trigger = true; current += `${raw}\n`;
      const complete = trigger ? line.toUpperCase().endsWith('END;') : line.endsWith(';');
      if (complete) { result.push(current.trim().replace(/;$/, '')); current = ''; trigger = false; }
    }
    if (current.trim().length > 0) throw new Error('Incomplete schema migration'); return result;
  }
}
