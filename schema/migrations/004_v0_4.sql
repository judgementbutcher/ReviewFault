PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

-- Stable installation identity. Account binding is intentionally separate from
-- credentials, which live in each platform's secure storage.
CREATE TABLE local_device (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  device_id TEXT NOT NULL UNIQUE CHECK (length(device_id) = 36),
  workspace_id TEXT,
  account_id TEXT,
  next_counter INTEGER NOT NULL DEFAULT 1 CHECK (next_counter > 0),
  created_at INTEGER NOT NULL,
  CHECK ((workspace_id IS NULL) = (account_id IS NULL))
);

CREATE TABLE sync_cursor (
  workspace_id TEXT PRIMARY KEY,
  server_seq INTEGER NOT NULL DEFAULT 0 CHECK (server_seq >= 0),
  updated_at INTEGER NOT NULL
);

CREATE TABLE sync_revision (
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
  server_seq INTEGER NOT NULL DEFAULT 0 CHECK (server_seq >= 0),
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
  field_versions_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(field_versions_json)),
  PRIMARY KEY (entity_type, entity_id)
) WITHOUT ROWID;

CREATE TABLE sync_outbox (
  operation_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  device_counter INTEGER NOT NULL CHECK (device_counter > 0),
  base_cursor INTEGER NOT NULL CHECK (base_cursor >= 0),
  base_revision INTEGER NOT NULL DEFAULT 0 CHECK (base_revision >= 0),
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('create', 'update', 'delete', 'restore', 'add', 'remove')),
  changed_fields_json TEXT NOT NULL CHECK (json_valid(changed_fields_json)),
  occurred_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at INTEGER NOT NULL DEFAULT 0,
  last_error_code TEXT,
  UNIQUE (device_id, device_counter)
);

CREATE INDEX sync_outbox_retry_idx
  ON sync_outbox (next_attempt_at, device_counter);

CREATE TABLE sync_conflict (
  id TEXT PRIMARY KEY,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  field_name TEXT NOT NULL,
  accepted_value_json TEXT NOT NULL CHECK (json_valid(accepted_value_json)),
  candidate_value_json TEXT NOT NULL CHECK (json_valid(candidate_value_json)),
  accepted_operation_id TEXT NOT NULL,
  candidate_operation_id TEXT NOT NULL,
  server_seq INTEGER NOT NULL CHECK (server_seq > 0),
  created_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolution_operation_id TEXT,
  UNIQUE (entity_type, entity_id, field_name, candidate_operation_id)
);

-- Relationship changes are observed facts. A remove suppresses only adds known
-- to it; a later explicit add remains able to restore the relationship.
CREATE TABLE relation_operation (
  operation_id TEXT PRIMARY KEY,
  relation_type TEXT NOT NULL CHECK (relation_type IN ('study_item_tag', 'item_relation')),
  source_id TEXT NOT NULL,
  target_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('add', 'remove')),
  device_id TEXT NOT NULL,
  device_counter INTEGER NOT NULL CHECK (device_counter > 0),
  observed_adds_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(observed_adds_json)),
  occurred_at INTEGER NOT NULL,
  UNIQUE (device_id, device_counter)
);

-- review_action_v4 contains only portable facts. due_at and other calculated
-- decisions remain in the rebuildable schedule cache, never in this table.
CREATE TABLE review_action_v4 (
  action_id TEXT PRIMARY KEY,
  study_item_id TEXT NOT NULL REFERENCES study_item(id),
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  feedback INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL,
  duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  error_reason TEXT CHECK (error_reason IS NULL OR error_reason IN (
    'concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'
  )),
  hint_revealed INTEGER NOT NULL DEFAULT 0 CHECK (hint_revealed IN (0, 1)),
  device_id TEXT NOT NULL,
  device_counter INTEGER NOT NULL CHECK (device_counter > 0),
  causal_cursor INTEGER NOT NULL DEFAULT 0 CHECK (causal_cursor >= 0),
  source_generation INTEGER NOT NULL CHECK (source_generation BETWEEN 1 AND 4),
  created_at INTEGER NOT NULL,
  UNIQUE (device_id, device_counter),
  CHECK (
    (algorithm = 'memory_fsrs_6' AND feedback BETWEEN 1 AND 4) OR
    (algorithm = 'math_mastery_ladder' AND feedback BETWEEN 0 AND 3)
  )
);

CREATE INDEX review_action_v4_replay_idx
  ON review_action_v4 (study_item_id, causal_cursor, reviewed_at, action_id);

CREATE TRIGGER review_action_v4_no_update
BEFORE UPDATE ON review_action_v4
BEGIN
  SELECT RAISE(ABORT, 'review_action_v4 is immutable');
END;
CREATE TRIGGER review_action_v4_no_delete
BEFORE DELETE ON review_action_v4
BEGIN
  SELECT RAISE(ABORT, 'review_action_v4 is immutable');
END;

CREATE TABLE schedule_cache_v4 (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  scheduler_algorithm_version INTEGER NOT NULL DEFAULT 3 CHECK (scheduler_algorithm_version = 3),
  state_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(state_json)),
  due_at INTEGER NOT NULL DEFAULT 0,
  replayed_action_count INTEGER NOT NULL DEFAULT 0 CHECK (replayed_action_count >= 0),
  replay_fingerprint TEXT NOT NULL DEFAULT '',
  dirty INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
  rebuilt_at INTEGER
);

INSERT INTO schedule_cache_v4 (
  study_item_id, algorithm, due_at, replayed_action_count, dirty
)
SELECT study_item_id, algorithm, due_at, repetitions, 1
FROM schedule_state_v2;

CREATE TRIGGER study_item_v4_cache
AFTER INSERT ON study_item
BEGIN
  INSERT INTO schedule_cache_v4 (study_item_id, algorithm, due_at, replayed_action_count, dirty)
  VALUES (NEW.id,
    CASE NEW.kind WHEN 'memory_card' THEN 'memory_fsrs_6' ELSE 'math_mastery_ladder' END,
    0, 0, 1);
END;

-- Preserve old immutable event tables. Projection rows make old reviews part of
-- canonical replay without updating or deleting any source row.
INSERT OR IGNORE INTO review_action_v4 (
  action_id, study_item_id, algorithm, feedback, reviewed_at, duration_seconds,
  error_reason, hint_revealed, device_id, device_counter, causal_cursor,
  source_generation, created_at
)
SELECT 'v1:' || r.id, r.study_item_id,
       CASE s.kind WHEN 'memory_card' THEN 'memory_fsrs_6' ELSE 'math_mastery_ladder' END,
       CASE WHEN s.kind = 'memory_card' THEN r.rating ELSE MAX(0, r.rating - 1) END,
       r.reviewed_at, r.duration_seconds, NULL, 0, r.device_id,
       ROW_NUMBER() OVER (PARTITION BY r.device_id ORDER BY r.reviewed_at, r.id),
       0, 1, r.created_at
FROM review_log r
JOIN study_item s ON s.id = r.study_item_id
WHERE NOT EXISTS (SELECT 1 FROM review_event_v2 e WHERE e.id = r.id)
;

INSERT OR IGNORE INTO review_action_v4 (
  action_id, study_item_id, algorithm, feedback, reviewed_at, duration_seconds,
  error_reason, hint_revealed, device_id, device_counter, causal_cursor,
  source_generation, created_at
)
SELECT 'v2:' || e.id, e.study_item_id, e.algorithm, e.feedback, e.reviewed_at,
       e.duration_seconds, m.error_reason, COALESCE(m.hint_revealed, 0), e.device_id,
       1000000000 + ROW_NUMBER() OVER (PARTITION BY e.device_id ORDER BY e.reviewed_at, e.id),
       0, 2, e.created_at
FROM review_event_v2 e
LEFT JOIN math_review_event_v2 m ON m.review_event_id = e.id;

INSERT OR IGNORE INTO review_action_v4 (
  action_id, study_item_id, algorithm, feedback, reviewed_at, duration_seconds,
  error_reason, hint_revealed, device_id, device_counter, causal_cursor,
  source_generation, created_at
)
SELECT 'v3:' || e.id, e.study_item_id, e.algorithm, e.feedback, e.reviewed_at,
       e.duration_seconds, m.error_reason, COALESCE(m.hint_revealed, 0), e.device_id,
       2000000000 + ROW_NUMBER() OVER (PARTITION BY e.device_id ORDER BY e.reviewed_at, e.id),
       0, 3, e.created_at
FROM review_event_v3 e
LEFT JOIN math_review_event_v3 m ON m.review_event_id = e.id;

CREATE TABLE attempt_artifact (
  id TEXT PRIMARY KEY,
  attempt_id TEXT NOT NULL REFERENCES attempt(id),
  artifact_type TEXT NOT NULL CHECK (artifact_type IN ('reviewfault-ink-v1', 'png-preview', 'annotated-image')),
  media_id TEXT NOT NULL REFERENCES media(id),
  background_media_sha256 TEXT,
  page_count INTEGER NOT NULL DEFAULT 1 CHECK (page_count > 0),
  created_at INTEGER NOT NULL,
  UNIQUE (attempt_id, artifact_type, media_id)
);

CREATE TABLE local_ink_draft (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  format_version INTEGER NOT NULL DEFAULT 1 CHECK (format_version = 1),
  gzip_json BLOB NOT NULL,
  preview_png BLOB,
  updated_at INTEGER NOT NULL
);

CREATE TRIGGER attempt_artifact_no_update
BEFORE UPDATE ON attempt_artifact
BEGIN
  SELECT RAISE(ABORT, 'attempt_artifact is immutable');
END;
CREATE TRIGGER attempt_artifact_no_delete
BEFORE DELETE ON attempt_artifact
BEGIN
  SELECT RAISE(ABORT, 'attempt_artifact is immutable');
END;

-- Portable content mutations automatically append an operation in the same
-- SQLite transaction. Schedule/cache columns deliberately have no trigger.
CREATE TRIGGER sync_study_item_insert
AFTER INSERT ON study_item
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
      substr(lower(hex(randomblob(2))), 2) || '-' ||
      substr('89ab', abs(random()) % 4 + 1, 1) ||
      substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id =
      (SELECT workspace_id FROM local_device WHERE singleton = 1)), 0),
    0, 'studyItem', NEW.id, 'create',
    json_object('kind', NEW.kind, 'subject', NEW.subject, 'chapterId', NEW.chapter_id),
    NEW.created_at
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_study_item_deletion
AFTER UPDATE OF deleted_at ON study_item
WHEN OLD.deleted_at IS NOT NEW.deleted_at AND
     EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
      substr(lower(hex(randomblob(2))), 2) || '-' ||
      substr('89ab', abs(random()) % 4 + 1, 1) ||
      substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id =
      (SELECT workspace_id FROM local_device WHERE singleton = 1)), 0),
    COALESCE((SELECT revision FROM sync_revision WHERE entity_type = 'studyItem' AND entity_id = NEW.id), 0),
    'studyItem', NEW.id, CASE WHEN NEW.deleted_at IS NULL THEN 'restore' ELSE 'delete' END,
    json_object('deletedAt', NEW.deleted_at), NEW.updated_at
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_math_problem_insert
AFTER INSERT ON math_problem
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    0, 'mathProblem', NEW.study_item_id, 'create',
    json_object('sourceName', NEW.source_name, 'promptMarkdown', NEW.prompt_markdown,
      'solutionMarkdown', NEW.solution_markdown, 'wrongStepMarkdown', NEW.wrong_step_markdown,
      'keyHintMarkdown', NEW.key_hint_markdown, 'defaultErrorReason', NEW.default_error_reason),
    CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_math_problem_update
AFTER UPDATE ON math_problem
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    COALESCE((SELECT revision FROM sync_revision WHERE entity_type = 'mathProblem' AND entity_id = NEW.study_item_id), 0),
    'mathProblem', NEW.study_item_id, 'update',
    json_object('sourceName', NEW.source_name, 'promptMarkdown', NEW.prompt_markdown,
      'solutionMarkdown', NEW.solution_markdown, 'wrongStepMarkdown', NEW.wrong_step_markdown,
      'keyHintMarkdown', NEW.key_hint_markdown, 'defaultErrorReason', NEW.default_error_reason),
    CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_memory_card_insert
AFTER INSERT ON memory_card
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    0, 'memoryCard', NEW.study_item_id, 'create',
    json_object('templateType', NEW.template_type, 'promptMarkdown', NEW.prompt_markdown,
      'answerMarkdown', NEW.answer_markdown, 'hints', json(NEW.hints_json),
      'answerPoints', json(NEW.answer_points_json), 'occlusions', json(NEW.occlusions_json)),
    CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_memory_card_update
AFTER UPDATE ON memory_card
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    COALESCE((SELECT revision FROM sync_revision WHERE entity_type = 'memoryCard' AND entity_id = NEW.study_item_id), 0),
    'memoryCard', NEW.study_item_id, 'update',
    json_object('templateType', NEW.template_type, 'promptMarkdown', NEW.prompt_markdown,
      'answerMarkdown', NEW.answer_markdown, 'hints', json(NEW.hints_json),
      'answerPoints', json(NEW.answer_points_json), 'occlusions', json(NEW.occlusions_json)),
    CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_learning_preferences_update
AFTER UPDATE ON learning_preferences
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    COALESCE((SELECT revision FROM sync_revision WHERE entity_type = 'learningPreferences' AND entity_id = 'singleton'), 0),
    'learningPreferences', 'singleton', 'update',
    json_object('dailyNewMemoryLimit', NEW.daily_new_memory_limit,
      'sessionMinutes', NEW.session_minutes, 'memoryPreset', NEW.memory_preset,
      'mathIntensity', NEW.math_intensity, 'schedulerGeneration', NEW.scheduler_generation),
    NEW.updated_at
  FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_study_item_tag_add
AFTER INSERT ON study_item_tag
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO relation_operation (
    operation_id, relation_type, source_id, target_id, action, device_id,
    device_counter, observed_adds_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    'study_item_tag', NEW.study_item_id, NEW.tag_id, 'add', device_id,
    next_counter - 1, '[]', CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT operation_id, device_id, device_counter,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id =
      (SELECT workspace_id FROM local_device WHERE singleton = 1)), 0),
    0, 'relation',
    source_id || ':' || target_id, 'add',
    json_object('relationType', relation_type, 'sourceId', source_id, 'targetId', target_id),
    occurred_at FROM relation_operation
  WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
    AND device_counter = (SELECT next_counter - 1 FROM local_device WHERE singleton = 1);
END;

CREATE TRIGGER sync_study_item_tag_remove
AFTER DELETE ON study_item_tag
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO relation_operation (
    operation_id, relation_type, source_id, target_id, action, device_id,
    device_counter, observed_adds_json, occurred_at
  ) SELECT
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    'study_item_tag', OLD.study_item_id, OLD.tag_id, 'remove', device_id,
    next_counter - 1,
    COALESCE((SELECT json_group_array(operation_id) FROM relation_operation
      WHERE relation_type = 'study_item_tag' AND source_id = OLD.study_item_id
        AND target_id = OLD.tag_id AND action = 'add'), '[]'),
    CAST(strftime('%s', 'now') AS INTEGER)
  FROM local_device WHERE singleton = 1;
  INSERT INTO sync_outbox (
    operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at
  ) SELECT operation_id, device_id, device_counter,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id =
      (SELECT workspace_id FROM local_device WHERE singleton = 1)), 0),
    0, 'relation',
    source_id || ':' || target_id, 'remove',
    json_object('relationType', relation_type, 'sourceId', source_id,
      'targetId', target_id, 'observedAdds', json(observed_adds_json)), occurred_at
  FROM relation_operation
  WHERE device_id = (SELECT device_id FROM local_device WHERE singleton = 1)
    AND device_counter = (SELECT next_counter - 1 FROM local_device WHERE singleton = 1);
END;

UPDATE schema_metadata SET schema_version = 4 WHERE singleton = 1;
PRAGMA user_version = 4;

COMMIT;
