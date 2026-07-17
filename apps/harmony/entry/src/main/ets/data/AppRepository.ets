import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo as fs } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { zlib } from '@kit.BasicServicesKit';
import reviewfault from 'libreviewfault.so';
import { NativeScheduler, ScheduleCard, ScheduleResult, MathScheduleResult } from '../core/NativeScheduler';

export interface DashboardSummary {
  overdue: number;
  dueToday: number;
  newItems: number;
  estimatedMinutes: number;
}

export interface StudyRow {
  id: string;
  kind: string;
  subject: string;
  state: number;
  difficulty: number;
  stabilityDays: number;
  dueAt: number;
  lastReviewedAt: number;
  repetitions: number;
  lapses: number;
  prompt: string;
  answer: string;
  mediaPath?: string;
  templateType: string;
  structuredJson: string;
}

export interface LearningPreferences {
  dailyNewMemoryLimit: number;
  sessionMinutes: number;
  memoryPreset: string;
  mathIntensity: string;
}

interface BackupFileEntry {
  path: string;
  sha256: string;
  bytes: number;
}

interface BackupManifest {
  format: string;
  version: number;
  appVersion?: string;
  schemaVersion: number;
  schedulerAbiVersion: number;
  exportedAt: number;
  files: BackupFileEntry[];
}

export class AppRepository {
  // v1 review_log is retained read-only for first-use v2 history replay.
  private static instance: AppRepository = new AppRepository();
  private store?: relationalStore.RdbStore;

  static shared(): AppRepository {
    return AppRepository.instance;
  }

  async initialize(context: common.UIAbilityContext): Promise<void> {
    if (this.store !== undefined) {
      return;
    }
    this.store = await relationalStore.getRdbStore(context, {
      name: 'reviewfault.db',
      securityLevel: relationalStore.SecurityLevel.S1,
    });
    await this.store.executeSql('PRAGMA foreign_keys = ON');
    const versionResult = await this.store.querySql('PRAGMA user_version');
    let version: number = 0;
    if (versionResult.goToFirstRow()) {
      version = versionResult.getLong(0);
    }
    versionResult.close();
    if (version === 0) {
      const statements = AppRepository.migrationStatements(reviewfault.schemaV1());
      this.store.beginTransaction();
      try {
        for (let index = 0; index < statements.length; index++) {
          await this.store.executeSql(statements[index]);
        }
        await this.store.executeSql('PRAGMA user_version = 1');
        this.store.commit();
        version = 1;
      } catch (error) {
        this.store.rollBack();
        throw error;
      }
    }
    if (version === 1) {
      const statements = AppRepository.migrationStatements(reviewfault.schemaV2());
      this.store.beginTransaction();
      try {
        for (let index = 0; index < statements.length; index++) {
          await this.store.executeSql(statements[index]);
        }
        await this.store.executeSql('PRAGMA user_version = 2');
        this.store.commit();
        version = 2;
      } catch (error) {
        this.store.rollBack();
        throw error;
      }
    }
    if (version !== 2) {
      throw new Error(`不支持的数据库版本：${version}`);
    }
    NativeScheduler.validateAbi();
  }

  async dashboard(now: number, dayStart: number): Promise<DashboardSummary> {
    const result = await this.requiredStore().querySql(`
      SELECT
        COALESCE(SUM(CASE WHEN scheduler_state <> 0 AND due_at < ? THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN scheduler_state <> 0 AND due_at BETWEEN ? AND ? THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN scheduler_state = 0 THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN scheduler_state = 0 OR due_at <= ?
          THEN CASE WHEN kind = 'math_problem' THEN 480 ELSE 45 END ELSE 0 END), 0)
      FROM study_item
      WHERE suspended_at IS NULL AND deleted_at IS NULL
    `, [dayStart, dayStart, now, now]);
    result.goToFirstRow();
    const summary: DashboardSummary = {
      overdue: result.getLong(0),
      dueToday: result.getLong(1),
      newItems: result.getLong(2),
      estimatedMinutes: Math.floor((result.getLong(3) + 59) / 60),
    };
    result.close();
    return summary;
  }

  async nextForReview(now: number): Promise<StudyRow | undefined> {
    const localDate = new Date(now * 1000);
    localDate.setHours(0, 0, 0, 0);
    const dayStart = Math.floor(localDate.getTime() / 1000);
    const result = await this.requiredStore().querySql(`
      SELECT s.id, s.kind, s.subject, s.scheduler_state, s.difficulty,
             s.stability_days, s.due_at, s.last_reviewed_at,
             s.repetitions, s.lapses,
             COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
             COALESCE(m.answer_markdown, p.solution_markdown, ''),
             media.relative_path, COALESCE(m.template_type, ''),
             CASE WHEN m.template_type = 'layered_hint' THEN m.hints_json
                  WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                  ELSE '[]' END
      FROM study_item s
      LEFT JOIN memory_card m ON m.study_item_id = s.id
      LEFT JOIN math_problem p ON p.study_item_id = s.id
      LEFT JOIN math_problem_media pm
        ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
      LEFT JOIN media ON media.id = pm.media_id
      WHERE s.suspended_at IS NULL AND s.deleted_at IS NULL
        AND ((s.scheduler_state <> 0 AND s.due_at <= ?)
          OR (s.scheduler_state = 0 AND (s.kind = 'math_problem' OR (
            SELECT COUNT(*) FROM review_event_v2 e
            JOIN memory_review_event_v2 m ON m.review_event_id = e.id
            WHERE m.state_before = 0 AND e.reviewed_at >= ?
          ) < (SELECT daily_new_memory_limit FROM learning_preferences WHERE singleton = 1))))
      ORDER BY
        CASE WHEN s.scheduler_state <> 0 AND s.due_at < ? THEN 0
             WHEN s.scheduler_state <> 0 THEN 1 ELSE 2 END,
        CASE WHEN s.scheduler_state <> 0 AND s.due_at < ?
             THEN CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END ELSE 0 END,
        CASE WHEN s.kind = 'memory_card' THEN 0 ELSE 1 END,
        s.due_at, s.created_at
      LIMIT 1
    `, [now, dayStart, dayStart, dayStart]);
    if (!result.goToFirstRow()) {
      result.close();
      return undefined;
    }
    const row: StudyRow = {
      id: result.getString(0),
      kind: result.getString(1),
      subject: result.getString(2),
      state: result.getLong(3),
      difficulty: result.getDouble(4),
      stabilityDays: result.getDouble(5),
      dueAt: result.getLong(6),
      lastReviewedAt: result.getLong(7),
      repetitions: result.getLong(8),
      lapses: result.getLong(9),
      prompt: result.getString(10),
      answer: result.getString(11),
      templateType: result.getString(13),
      structuredJson: result.getString(14),
    };
    if (!result.isColumnNull(12)) {
      row.mediaPath = result.getString(12);
    }
    result.close();
    return row;
  }

  async search(query: string, deletedOnly: boolean = false): Promise<StudyRow[]> {
    const pattern = `%${query.trim().replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_')}%`;
    const result = await this.requiredStore().querySql(`
      SELECT s.id, s.kind, s.subject, s.scheduler_state, s.difficulty,
             s.stability_days, s.due_at, s.last_reviewed_at,
             s.repetitions, s.lapses,
             COALESCE(m.prompt_markdown, p.prompt_markdown, ''),
             COALESCE(m.answer_markdown, p.solution_markdown, ''), media.relative_path,
             COALESCE(m.template_type, ''),
             CASE WHEN m.template_type = 'layered_hint' THEN m.hints_json
                  WHEN m.template_type = 'enumeration' THEN m.answer_points_json
                  ELSE '[]' END
      FROM study_item s
      LEFT JOIN memory_card m ON m.study_item_id = s.id
      LEFT JOIN math_problem p ON p.study_item_id = s.id
      LEFT JOIN math_problem_media pm
        ON pm.math_problem_id = s.id AND pm.role = 'prompt' AND pm.sort_order = 0
      LEFT JOIN media ON media.id = pm.media_id
      WHERE ${deletedOnly ? 's.deleted_at IS NOT NULL' : 's.deleted_at IS NULL'} AND (
        ? = '%%' OR COALESCE(m.prompt_markdown, p.prompt_markdown, '') LIKE ? ESCAPE '\'
        OR COALESCE(m.answer_markdown, p.solution_markdown, '') LIKE ? ESCAPE '\'
        OR COALESCE(p.source_name, '') LIKE ? ESCAPE '\')
      ORDER BY s.updated_at DESC LIMIT 100
    `, [pattern, pattern, pattern, pattern]);
    const rows: StudyRow[] = [];
    if (result.goToFirstRow()) {
      do {
        const row: StudyRow = {
          id: result.getString(0), kind: result.getString(1), subject: result.getString(2),
          state: result.getLong(3), difficulty: result.getDouble(4),
          stabilityDays: result.getDouble(5), dueAt: result.getLong(6),
          lastReviewedAt: result.getLong(7), repetitions: result.getLong(8),
          lapses: result.getLong(9), prompt: result.getString(10), answer: result.getString(11),
          templateType: result.getString(13), structuredJson: result.getString(14),
        };
        if (!result.isColumnNull(12)) row.mediaPath = result.getString(12);
        rows.push(row);
      } while (result.goToNextRow());
    }
    result.close();
    return rows;
  }

  async createMemoryCard(templateType: string, subject: string, prompt: string,
    answer: string, structuredLines: string[]): Promise<string> {
    if (prompt.trim().length === 0) {
      throw new Error('题干不能为空');
    }
    if ((templateType === 'qa' || templateType === 'comparison') && answer.trim().length === 0) {
      throw new Error('答案不能为空');
    }
    if (templateType === 'cloze' && !/\{\{c\d+::.+?}}/.test(prompt)) {
      throw new Error('填空题干缺少结构化标记');
    }
    if (templateType === 'layered_hint' &&
      (answer.trim().length === 0 || structuredLines.length === 0)) {
      throw new Error('分层提示卡需要答案和提示');
    }
    if (templateType === 'enumeration' && structuredLines.length < 2) {
      throw new Error('枚举卡至少需要两个要点');
    }
    const id = AppRepository.newId();
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    store.beginTransaction();
    try {
      await store.executeSql(`
        INSERT INTO study_item (id, kind, subject, created_at, updated_at)
        VALUES (?, 'memory_card', ?, ?, ?)
      `, [id, subject, now, now]);
      const hints = templateType === 'layered_hint' ? JSON.stringify(structuredLines) : '[]';
      const points = templateType === 'enumeration' ? JSON.stringify(structuredLines) : '[]';
      await store.executeSql(`
        INSERT INTO memory_card (
          study_item_id, template_type, prompt_markdown, answer_markdown,
          hints_json, answer_points_json
        ) VALUES (?, ?, ?, ?, ?, ?)
      `, [id, templateType, prompt.trim(), answer.trim(), hints, points]);
      store.commit();
      return id;
    } catch (error) {
      store.rollBack();
      throw error;
    }
  }

  async createMathProblem(prompt: string, sourceName: string): Promise<string> {
    if (prompt.trim().length === 0) {
      throw new Error('题面不能为空');
    }
    const id = AppRepository.newId();
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    store.beginTransaction();
    try {
      await store.executeSql(`
        INSERT INTO study_item (id, kind, subject, created_at, updated_at)
        VALUES (?, 'math_problem', 'math', ?, ?)
      `, [id, now, now]);
      await store.executeSql(`
        INSERT INTO math_problem (study_item_id, source_name, prompt_markdown)
        VALUES (?, ?, ?)
      `, [id, sourceName.trim(), prompt.trim()]);
      store.commit();
      return id;
    } catch (error) {
      store.rollBack();
      throw error;
    }
  }

  async createMathProblemFromImage(context: common.UIAbilityContext, uri: string,
    sourceName: string): Promise<string> {
    const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    let data: ArrayBuffer;
    try {
      const stat = fs.statSync(source.fd);
      if (stat.size <= 0) {
        throw new Error('图片为空');
      }
      data = new ArrayBuffer(stat.size);
      fs.readSync(source.fd, data);
    } finally {
      fs.closeSync(source.fd);
    }
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: new Uint8Array(data) });
    const digestResult = await digest.digest();
    let sha256 = '';
    for (let index = 0; index < digestResult.data.length; index++) {
      sha256 += digestResult.data[index].toString(16).padStart(2, '0');
    }
    const mediaDirectory = `${context.filesDir}/media`;
    try {
      fs.mkdirSync(mediaDirectory);
    } catch (_) {
      // Existing media directory is expected after the first capture.
    }
    const relativePath = `media/${sha256}.jpg`;
    const destinationPath = `${context.filesDir}/${relativePath}`;
    if (!fs.accessSync(destinationPath)) {
      const destination = fs.openSync(destinationPath,
        fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(destination.fd, data);
      } finally {
        fs.closeSync(destination.fd);
      }
    }

    const problemId = AppRepository.newId();
    const mediaId = AppRepository.newId();
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    store.beginTransaction();
    try {
      await store.executeSql(`
        INSERT INTO study_item (id, kind, subject, created_at, updated_at)
        VALUES (?, 'math_problem', 'math', ?, ?)
      `, [problemId, now, now]);
      await store.executeSql(`
        INSERT INTO math_problem (study_item_id, source_name) VALUES (?, ?)
      `, [problemId, sourceName.trim()]);
      await store.executeSql(`
        INSERT OR IGNORE INTO media
          (id, sha256, mime_type, byte_count, relative_path, created_at)
        VALUES (?, ?, 'image/jpeg', ?, ?, ?)
      `, [mediaId, sha256, data.byteLength, relativePath, now]);
      const lookup = await store.querySql('SELECT id FROM media WHERE sha256 = ?', [sha256]);
      if (!lookup.goToFirstRow()) {
        lookup.close();
        throw new Error('媒体索引写入失败');
      }
      const storedMediaId = lookup.getString(0);
      lookup.close();
      await store.executeSql(`
        INSERT INTO math_problem_media (math_problem_id, media_id, role, sort_order)
        VALUES (?, ?, 'prompt', 0)
      `, [problemId, storedMediaId]);
      store.commit();
      return problemId;
    } catch (error) {
      store.rollBack();
      throw error;
    }
  }

  async createMathProblemFromImages(context: common.UIAbilityContext, uris: string[],
    sourceName: string): Promise<string> {
    if (uris.length < 1 || uris.length > 5) throw new Error('每道题请选择 1–5 张图片');
    const problemId = await this.createMathProblemFromImage(context, uris[0], sourceName);
    for (let index = 1; index < uris.length; index++) {
      await this.attachMathImage(context, problemId, uris[index], index);
    }
    return problemId;
  }

  async mediaPaths(mathProblemId: string): Promise<string[]> {
    const result = await this.requiredStore().querySql(`
      SELECT media.relative_path FROM math_problem_media pm
      JOIN media ON media.id = pm.media_id
      WHERE pm.math_problem_id = ? AND pm.role = 'prompt' AND media.deleted_at IS NULL
      ORDER BY pm.sort_order
    `, [mathProblemId]);
    const paths: string[] = [];
    if (result.goToFirstRow()) {
      do { paths.push(result.getString(0)); } while (result.goToNextRow());
    }
    result.close();
    return paths;
  }

  private async attachMathImage(context: common.UIAbilityContext, problemId: string,
    uri: string, sortOrder: number): Promise<void> {
    const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    let data: ArrayBuffer;
    try {
      const stat = fs.statSync(source.fd);
      if (stat.size <= 0) throw new Error('图片为空');
      data = new ArrayBuffer(stat.size);
      fs.readSync(source.fd, data);
    } finally {
      fs.closeSync(source.fd);
    }
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: new Uint8Array(data) });
    const digestResult = await digest.digest();
    let sha256 = '';
    for (let index = 0; index < digestResult.data.length; index++) {
      sha256 += digestResult.data[index].toString(16).padStart(2, '0');
    }
    const mediaDirectory = `${context.filesDir}/media`;
    if (!fs.accessSync(mediaDirectory)) fs.mkdirSync(mediaDirectory);
    const relativePath = `media/${sha256}.jpg`;
    const destinationPath = `${context.filesDir}/${relativePath}`;
    if (!fs.accessSync(destinationPath)) {
      const destination = fs.openSync(destinationPath,
        fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try { fs.writeSync(destination.fd, data); } finally { fs.closeSync(destination.fd); }
    }
    const mediaId = AppRepository.newId();
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    await store.executeSql(`
      INSERT OR IGNORE INTO media
        (id, sha256, mime_type, byte_count, relative_path, created_at)
      VALUES (?, ?, 'image/jpeg', ?, ?, ?)
    `, [mediaId, sha256, data.byteLength, relativePath, now]);
    const lookup = await store.querySql('SELECT id FROM media WHERE sha256 = ?', [sha256]);
    if (!lookup.goToFirstRow()) { lookup.close(); throw new Error('媒体索引写入失败'); }
    const storedMediaId = lookup.getString(0);
    lookup.close();
    await store.executeSql(`
      INSERT OR IGNORE INTO math_problem_media (math_problem_id, media_id, role, sort_order)
      VALUES (?, ?, 'prompt', ?)
    `, [problemId, storedMediaId, sortOrder]);
  }

  async updateMathDetails(id: string, solution: string, wrongStep: string,
    keyHint: string, errorReason?: string): Promise<void> {
    await this.requiredStore().executeSql(`
      UPDATE math_problem SET solution_markdown = ?, wrong_step_markdown = ?,
        key_hint_markdown = ?, default_error_reason = ? WHERE study_item_id = ?
    `, [solution.trim(), wrongStep.trim(), keyHint.trim(), errorReason ?? null, id]);
    await this.requiredStore().executeSql(
      'UPDATE study_item SET updated_at = ? WHERE id = ?',
      [Math.floor(Date.now() / 1000), id]);
  }

  async updateMemoryCard(id: string, prompt: string, answer: string): Promise<void> {
    if (prompt.trim().length === 0) throw new Error('题干不能为空');
    await this.requiredStore().executeSql(`
      UPDATE memory_card SET prompt_markdown = ?, answer_markdown = ? WHERE study_item_id = ?
    `, [prompt.trim(), answer.trim(), id]);
    await this.requiredStore().executeSql(
      'UPDATE study_item SET updated_at = ? WHERE id = ?',
      [Math.floor(Date.now() / 1000), id]);
  }

  async learningPreferences(): Promise<LearningPreferences> {
    const result = await this.requiredStore().querySql(`SELECT daily_new_memory_limit,
      session_minutes, memory_preset, math_intensity FROM learning_preferences WHERE singleton = 1`);
    if (!result.goToFirstRow()) { result.close(); throw new Error('学习设置不存在'); }
    const value: LearningPreferences = {
      dailyNewMemoryLimit: result.getLong(0), sessionMinutes: result.getLong(1),
      memoryPreset: result.getString(2), mathIntensity: result.getString(3),
    };
    result.close();
    return value;
  }

  async saveLearningPreferences(value: LearningPreferences): Promise<void> {
    if (value.dailyNewMemoryLimit < 0 || value.dailyNewMemoryLimit > 500 ||
      value.sessionMinutes < 1 || value.sessionMinutes > 240) throw new Error('学习设置超出范围');
    await this.requiredStore().executeSql(`UPDATE learning_preferences SET
      daily_new_memory_limit = ?, session_minutes = ?, memory_preset = ?,
      math_intensity = ?, updated_at = ? WHERE singleton = 1`,
    [value.dailyNewMemoryLimit, value.sessionMinutes, value.memoryPreset,
      value.mathIntensity, Math.floor(Date.now() / 1000)]);
  }

  async softDelete(ids: string[]): Promise<number> {
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    store.beginTransaction();
    try {
      for (let index = 0; index < ids.length; index++) {
        await store.executeSql('UPDATE study_item SET deleted_at = ?, updated_at = ? WHERE id = ?',
          [now, now, ids[index]]);
      }
      store.commit();
      return now + 10;
    } catch (error) { store.rollBack(); throw error; }
  }

  async restore(ids: string[]): Promise<void> {
    for (let index = 0; index < ids.length; index++) {
      await this.requiredStore().executeSql(
        'UPDATE study_item SET deleted_at = NULL, updated_at = ? WHERE id = ?',
        [Math.floor(Date.now() / 1000), ids[index]]);
    }
  }

  async review(row: StudyRow, rating: number, reviewedAt: number,
    durationSeconds: number, mathResult?: string, errorReason?: string,
    hintRevealed: boolean = false): Promise<ScheduleResult> {
    const card: ScheduleCard = {
      state: row.state,
      difficulty: row.difficulty,
      stabilityDays: row.stabilityDays,
      dueAt: row.dueAt,
      lastReviewedAt: row.lastReviewedAt,
      repetitions: row.repetitions,
      lapses: row.lapses,
    };
    const preferenceResult = await this.requiredStore().querySql(
      'SELECT memory_preset, math_intensity FROM learning_preferences WHERE singleton = 1');
    preferenceResult.goToFirstRow();
    const memoryPreference = preferenceResult.getString(0);
    const mathPreference = preferenceResult.getString(1);
    preferenceResult.close();
    const preset = memoryPreference === 'time_saving' ? 0 : memoryPreference === 'reinforced' ? 2 : 1;
    const intensity = mathPreference === 'intensive' ? 0 : mathPreference === 'relaxed' ? 2 : 1;
    let mathScheduled: MathScheduleResult | undefined = undefined;
    let masteryBefore: number = 0;
    let streakBefore: number = 0;
    let scheduled: ScheduleResult;
    if (row.kind === 'memory_card') {
      scheduled = NativeScheduler.reviewMemoryV2(card, rating, reviewedAt, preset);
    } else {
      const mathState = await this.requiredStore().querySql(`
        SELECT m.mastery_level, m.fluent_streak, s.repetitions
        FROM math_schedule_state m JOIN schedule_state_v2 s USING (study_item_id)
        WHERE m.study_item_id = ?`, [row.id]);
      if (!mathState.goToFirstRow()) { mathState.close(); throw new Error('数学调度状态不存在'); }
      masteryBefore = mathState.getLong(0);
      streakBefore = mathState.getLong(1);
      const repetitions = mathState.getLong(2);
      mathState.close();
      const feedback = mathResult === 'again' ? 0 : mathResult === 'wrong' ? 1 :
        mathResult === 'effortful' ? 2 : mathResult === 'fluent' ? 3 : -1;
      if (feedback < 0) throw new Error('数学评分缺失');
      const reason = errorReason === 'concept' ? 1 : errorReason === 'approach' ? 2 :
        errorReason === 'calculation' ? 3 : errorReason === 'misread' ? 4 :
        errorReason === 'forgotten_fact' ? 5 : errorReason === 'timeout' ? 6 :
        errorReason === undefined ? 0 : 7;
      mathScheduled = NativeScheduler.reviewMathV2(masteryBefore, streakBefore, row.dueAt,
        row.lastReviewedAt, repetitions, feedback, reason, hintRevealed, reviewedAt, intensity);
      scheduled = {
        state: 2, difficulty: mathScheduled.masteryLevel + 1,
        stabilityDays: mathScheduled.scheduledDays, dueAt: mathScheduled.dueAt,
        repetitions: mathScheduled.repetitions,
        lapses: row.lapses + (feedback <= 1 ? 1 : 0),
        scheduledDays: mathScheduled.scheduledDays, retrievabilityBefore: 0,
      };
    }
    const elapsed = row.lastReviewedAt === 0 ? 0 : (reviewedAt - row.lastReviewedAt) / 86400;
    const now = Math.floor(Date.now() / 1000);
    const store = this.requiredStore();
    store.beginTransaction();
    try {
      await store.executeSql(`
        UPDATE study_item SET scheduler_state = ?, difficulty = ?, stability_days = ?,
          due_at = ?, last_reviewed_at = ?, repetitions = ?, lapses = ?, updated_at = ?
        WHERE id = ? AND repetitions = ?
      `, [scheduled.state, scheduled.difficulty, scheduled.stabilityDays,
        scheduled.dueAt, reviewedAt, scheduled.repetitions, scheduled.lapses,
        now, row.id, row.repetitions]);
      await store.executeSql(`UPDATE schedule_state_v2 SET due_at = ?, last_reviewed_at = ?,
        repetitions = ?, needs_history_replay = 0, updated_at = ? WHERE study_item_id = ?`,
      [scheduled.dueAt, reviewedAt, scheduled.repetitions, now, row.id]);
      const eventId = AppRepository.newId();
      await store.executeSql(`INSERT INTO review_event_v2 (id, study_item_id, algorithm,
        algorithm_version, parameter_version, preference, feedback, reviewed_at,
        duration_seconds, due_at_before, due_at_after, device_id, created_at)
        VALUES (?, ?, ?, 2, 1, ?, ?, ?, ?, ?, ?, 'harmony-local', ?)`,
      [eventId, row.id, row.kind === 'memory_card' ? 'memory_fsrs_6' : 'math_mastery_ladder',
        row.kind === 'memory_card' ? memoryPreference : mathPreference,
        row.kind === 'memory_card' ? rating : mathScheduled!.appliedFeedback,
        reviewedAt, Math.max(0, durationSeconds), row.dueAt, scheduled.dueAt, now]);
      if (row.kind === 'memory_card') {
        await store.executeSql(`UPDATE memory_schedule_state SET state = ?, difficulty = ?,
          stability_days = ?, lapses = ? WHERE study_item_id = ?`,
        [scheduled.state, scheduled.difficulty, scheduled.stabilityDays, scheduled.lapses, row.id]);
        await store.executeSql(`INSERT INTO memory_review_event_v2 (review_event_id,
          state_before, state_after, target_retention, elapsed_days, scheduled_days,
          retrievability_before, difficulty_before, difficulty_after,
          stability_before, stability_after) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [eventId, row.state, scheduled.state, preset === 0 ? 0.85 : preset === 2 ? 0.93 : 0.90,
          elapsed, scheduled.scheduledDays, scheduled.retrievabilityBefore, row.difficulty,
          scheduled.difficulty, row.stabilityDays, scheduled.stabilityDays]);
      }
      if (row.kind === 'math_problem' && mathResult !== undefined) {
        const attemptId = AppRepository.newId();
        await store.executeSql(`
          INSERT INTO attempt (
            id, math_problem_id, started_at, finished_at, result, error_reason, created_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
        `, [attemptId, row.id, reviewedAt - Math.max(0, durationSeconds), reviewedAt,
          mathResult, errorReason ?? null, now]);
        await store.executeSql(`UPDATE math_schedule_state SET mastery_level = ?,
          fluent_streak = ? WHERE study_item_id = ?`,
        [mathScheduled!.masteryLevel, mathScheduled!.fluentStreak, row.id]);
        const requested = mathResult === 'again' ? 0 : mathResult === 'wrong' ? 1 :
          mathResult === 'effortful' ? 2 : 3;
        await store.executeSql(`INSERT INTO math_review_event_v2 (review_event_id,
          attempt_id, requested_feedback, applied_feedback, error_reason, hint_revealed,
          mastery_before, mastery_after, fluent_streak_before, fluent_streak_after,
          scheduled_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [eventId, attemptId, requested, mathScheduled!.appliedFeedback, errorReason ?? null,
          hintRevealed ? 1 : 0, masteryBefore, mathScheduled!.masteryLevel, streakBefore,
          mathScheduled!.fluentStreak, mathScheduled!.scheduledDays]);
      }
      store.commit();
      return scheduled;
    } catch (error) {
      store.rollBack();
      throw error;
    }
  }

  async exportBackup(context: common.UIAbilityContext, destinationUri: string): Promise<void> {
    const token = util.generateRandomUUID(true);
    const staging = `${context.tempDir}/backup-${token}`;
    const archive = `${context.tempDir}/backup-${token}.zip`;
    fs.mkdirSync(staging);
    try {
      const databasePath = `${staging}/database.sqlite`;
      await this.requiredStore().backup(databasePath);
      const mediaSource = `${context.filesDir}/media`;
      const mediaDestination = `${staging}/media`;
      if (fs.accessSync(mediaSource)) {
        AppRepository.copyDirectory(mediaSource, mediaDestination);
      }
      const manifestEntries: BackupFileEntry[] = [];
      const payloadFiles = AppRepository.walkFiles(staging, '');
      for (let index = 0; index < payloadFiles.length; index++) {
        const relative = payloadFiles[index];
        manifestEntries.push({
          path: relative,
          sha256: await AppRepository.sha256File(`${staging}/${relative}`),
          bytes: fs.statSync(`${staging}/${relative}`).size,
        });
      }
      const manifest: BackupManifest = {
        format: 'reviewfault-backup',
        version: 2,
        appVersion: '0.2.0',
        schemaVersion: 2,
        schedulerAbiVersion: 2,
        exportedAt: Math.floor(Date.now() / 1000),
        files: manifestEntries,
      };
      AppRepository.writeText(`${staging}/manifest.json`, JSON.stringify(manifest, null, 2));
      const inputs: string[] = [`${staging}/manifest.json`, databasePath];
      if (fs.accessSync(mediaDestination)) inputs.push(mediaDestination);
      const options: zlib.Options = {
        level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION,
        memLevel: zlib.MemLevel.MEM_LEVEL_DEFAULT,
        strategy: zlib.CompressStrategy.COMPRESS_STRATEGY_DEFAULT_STRATEGY,
      };
      await zlib.compressFiles(inputs, archive, options);
      const output = fs.openSync(destinationUri,
        fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try {
        fs.copyFileSync(archive, output.fd);
      } finally {
        fs.closeSync(output.fd);
      }
    } finally {
      AppRepository.deleteTree(staging);
      try { fs.unlinkSync(archive); } catch (_) {}
    }
  }

  async restoreBackup(context: common.UIAbilityContext, sourceUri: string): Promise<void> {
    const token = util.generateRandomUUID(true);
    const archive = `${context.tempDir}/restore-${token}.zip`;
    const extracted = `${context.tempDir}/restore-${token}`;
    const rollbackDatabase = `${context.tempDir}/rollback-${token}.db`;
    const rollbackMedia = `${context.tempDir}/rollback-media-${token}`;
    fs.mkdirSync(extracted);
    const source = fs.openSync(sourceUri, fs.OpenMode.READ_ONLY);
    const destination = fs.openSync(archive,
      fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
    try {
      fs.copyFileSync(source.fd, destination.fd);
    } finally {
      fs.closeSync(source.fd);
      fs.closeSync(destination.fd);
    }
    try {
      const expandedSize = await zlib.getOriginalSize(archive);
      if (expandedSize > 2 * 1024 * 1024 * 1024) {
        throw new Error('备份解压后超过 2 GiB，已拒绝恢复');
      }
      await zlib.decompressFile(archive, extracted);
      const manifest = JSON.parse(AppRepository.readText(`${extracted}/manifest.json`)) as BackupManifest;
      const compatibleV1 = manifest.version === 1 && manifest.schemaVersion === 1 &&
        manifest.schedulerAbiVersion === 1;
      const compatibleV2 = manifest.version === 2 && manifest.schemaVersion === 2 &&
        manifest.schedulerAbiVersion === 2;
      if (manifest.format !== 'reviewfault-backup' || (!compatibleV1 && !compatibleV2)) {
        throw new Error('不是受支持的 ReviewFault 备份');
      }
      for (let index = 0; index < manifest.files.length; index++) {
        const listed = manifest.files[index];
        if (!AppRepository.isSafeBackupPath(listed.path)) {
          throw new Error('备份包含非法路径');
        }
        const filePath = `${extracted}/${listed.path}`;
        if (!fs.accessSync(filePath) || fs.statSync(filePath).size !== listed.bytes ||
          await AppRepository.sha256File(filePath) !== listed.sha256) {
          throw new Error(`备份文件校验失败：${listed.path}`);
        }
      }
      const restoredDatabase = `${extracted}/database.sqlite`;
      if (!fs.accessSync(restoredDatabase)) throw new Error('备份缺少数据库');

      const mediaDirectory = `${context.filesDir}/media`;
      if (fs.accessSync(mediaDirectory)) AppRepository.copyDirectory(mediaDirectory, rollbackMedia);
      await this.requiredStore().backup(rollbackDatabase);
      try {
        await this.requiredStore().restore(restoredDatabase);
        const restoredVersionResult = await this.requiredStore().querySql('PRAGMA user_version');
        let restoredVersion = restoredVersionResult.goToFirstRow() ?
          restoredVersionResult.getLong(0) : 0;
        restoredVersionResult.close();
        if (restoredVersion === 1) {
          const statements = AppRepository.migrationStatements(reviewfault.schemaV2());
          this.requiredStore().beginTransaction();
          try {
            for (let index = 0; index < statements.length; index++) {
              await this.requiredStore().executeSql(statements[index]);
            }
            await this.requiredStore().executeSql('PRAGMA user_version = 2');
            this.requiredStore().commit();
            restoredVersion = 2;
          } catch (error) {
            this.requiredStore().rollBack();
            throw error;
          }
        }
        const integrity = await this.requiredStore().querySql('PRAGMA integrity_check');
        const integrityOk = integrity.goToFirstRow() && integrity.getString(0) === 'ok';
        integrity.close();
        if (!integrityOk) throw new Error('备份数据库完整性检查失败');
        if (restoredVersion !== 2) throw new Error('备份数据库版本不兼容');
        const foreignKeys = await this.requiredStore().querySql('PRAGMA foreign_key_check');
        const hasBrokenReference = foreignKeys.goToFirstRow();
        foreignKeys.close();
        if (hasBrokenReference) throw new Error('备份数据库存在无效引用');

        if (fs.accessSync(mediaDirectory)) AppRepository.deleteTree(mediaDirectory);
        const restoredMedia = `${extracted}/media`;
        if (fs.accessSync(restoredMedia)) AppRepository.copyDirectory(restoredMedia, mediaDirectory);
      } catch (error) {
        await this.requiredStore().restore(rollbackDatabase);
        if (fs.accessSync(mediaDirectory)) AppRepository.deleteTree(mediaDirectory);
        if (fs.accessSync(rollbackMedia)) AppRepository.copyDirectory(rollbackMedia, mediaDirectory);
        throw error;
      }
    } finally {
      AppRepository.deleteTree(extracted);
      AppRepository.deleteTree(rollbackMedia);
      try { fs.unlinkSync(archive); } catch (_) {}
      try { fs.unlinkSync(rollbackDatabase); } catch (_) {}
    }
  }

  private requiredStore(): relationalStore.RdbStore {
    if (this.store === undefined) {
      throw new Error('数据库尚未初始化');
    }
    return this.store;
  }

  private static migrationStatements(script: string): string[] {
    const statements: string[] = [];
    const lines = script.split('\n');
    let current = '';
    let inTrigger = false;
    for (let index = 0; index < lines.length; index++) {
      const rawLine = lines[index];
      const line = rawLine.trim();
      if (line.length === 0 || line.startsWith('--') ||
        line.toUpperCase().startsWith('PRAGMA FOREIGN_KEYS') ||
        line.toUpperCase().startsWith('PRAGMA USER_VERSION') ||
        line.toUpperCase() === 'BEGIN IMMEDIATE;' || line.toUpperCase() === 'COMMIT;') {
        continue;
      }
      if (line.toUpperCase().startsWith('CREATE TRIGGER')) {
        inTrigger = true;
      }
      current += `${rawLine}\n`;
      const complete = inTrigger ? line.toUpperCase() === 'END;' : line.endsWith(';');
      if (complete) {
        statements.push(current.trim().replace(/;$/, ''));
        current = '';
        inTrigger = false;
      }
    }
    if (current.trim().length !== 0) {
      throw new Error('数据库迁移脚本不完整');
    }
    return statements;
  }

  private static walkFiles(directory: string, prefix: string): string[] {
    const output: string[] = [];
    const names = fs.listFileSync(directory);
    for (let index = 0; index < names.length; index++) {
      const relative = prefix.length === 0 ? names[index] : `${prefix}/${names[index]}`;
      const path = `${directory}/${names[index]}`;
      if (fs.statSync(path).isDirectory()) {
        output.push(...AppRepository.walkFiles(path, relative));
      } else {
        output.push(relative);
      }
    }
    return output;
  }

  private static copyDirectory(source: string, destination: string): void {
    if (!fs.accessSync(destination)) fs.mkdirSync(destination);
    const names = fs.listFileSync(source);
    for (let index = 0; index < names.length; index++) {
      const sourcePath = `${source}/${names[index]}`;
      const destinationPath = `${destination}/${names[index]}`;
      if (fs.statSync(sourcePath).isDirectory()) {
        AppRepository.copyDirectory(sourcePath, destinationPath);
      } else {
        fs.copyFileSync(sourcePath, destinationPath);
      }
    }
  }

  private static deleteTree(path: string): void {
    if (!fs.accessSync(path)) return;
    if (!fs.statSync(path).isDirectory()) {
      fs.unlinkSync(path);
      return;
    }
    const names = fs.listFileSync(path);
    for (let index = 0; index < names.length; index++) {
      AppRepository.deleteTree(`${path}/${names[index]}`);
    }
    fs.rmdirSync(path);
  }

  private static async sha256File(path: string): Promise<string> {
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    let data: ArrayBuffer;
    try {
      const size = fs.statSync(file.fd).size;
      data = new ArrayBuffer(size);
      fs.readSync(file.fd, data);
    } finally {
      fs.closeSync(file.fd);
    }
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: new Uint8Array(data) });
    const result = await digest.digest();
    let hash = '';
    for (let index = 0; index < result.data.length; index++) {
      hash += result.data[index].toString(16).padStart(2, '0');
    }
    return hash;
  }

  private static writeText(path: string, value: string): void {
    const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
    try { fs.writeSync(file.fd, value); } finally { fs.closeSync(file.fd); }
  }

  private static readText(path: string): string {
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      const size = fs.statSync(file.fd).size;
      const data = new ArrayBuffer(size);
      fs.readSync(file.fd, data);
      return new util.TextDecoder('utf-8').decodeWithStream(new Uint8Array(data));
    } finally {
      fs.closeSync(file.fd);
    }
  }

  private static isSafeBackupPath(path: string): boolean {
    if (path === 'database.sqlite') return true;
    if (!path.startsWith('media/') || path.includes('\\')) return false;
    const segments = path.split('/');
    return segments.every((segment) => segment.length > 0 && segment !== '.' && segment !== '..');
  }

  private static newId(): string {
    const bytes = cryptoFramework.createRandom().generateRandomSync(16).data;
    const timestamp = Date.now();
    for (let index = 0; index < 6; index++) {
      bytes[5 - index] = Math.floor(timestamp / Math.pow(2, index * 8)) & 0xff;
    }
    bytes[6] = 0x70 | (bytes[6] & 0x0f);
    bytes[8] = 0x80 | (bytes[8] & 0x3f);
    let hex = '';
    for (let index = 0; index < bytes.length; index++) {
      hex += bytes[index].toString(16).padStart(2, '0');
    }
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-` +
      `${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
}
