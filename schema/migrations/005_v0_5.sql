PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

-- v5 is deliberately additive.  The old study item and its v1-v4 immutable
-- history remain the source content/history; a unit only groups task routes.
CREATE TABLE learning_profile_v5 (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  exam_at INTEGER NOT NULL DEFAULT 1797724800 CHECK (exam_at > 0),
  daily_available_minutes INTEGER NOT NULL DEFAULT 60
    CHECK (daily_available_minutes BETWEEN 1 AND 1440),
  study_days_mask INTEGER NOT NULL DEFAULT 127 CHECK (study_days_mask BETWEEN 1 AND 127),
  math_percent INTEGER NOT NULL DEFAULT 50 CHECK (math_percent BETWEEN 0 AND 100),
  target_retention REAL NOT NULL DEFAULT 0.90 CHECK (target_retention BETWEEN 0.80 AND 0.99),
  updated_at INTEGER NOT NULL
);
INSERT INTO learning_profile_v5 (singleton, updated_at)
VALUES (1, CAST(strftime('%s', 'now') AS INTEGER));

CREATE TABLE learning_unit_v5 (
  id TEXT PRIMARY KEY,
  unit_type TEXT NOT NULL CHECK (unit_type IN ('math_error_cluster', 'memory_knowledge_package')),
  source_study_item_id TEXT UNIQUE REFERENCES study_item(id),
  subject TEXT NOT NULL CHECK (subject IN ('math', 'data_structures', 'computer_organization',
    'operating_systems', 'computer_networks')),
  chapter_id TEXT REFERENCES chapter(id),
  title TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE INDEX learning_unit_v5_subject_idx ON learning_unit_v5 (subject, chapter_id)
  WHERE deleted_at IS NULL;

-- A card remains compatible with the v1 prompt/answer tables, while this
-- profile stores the fields needed to create and evaluate it deliberately.
-- Payload is reserved for typed rows such as FLOPS scale mappings or formula
-- symbols; ordinary prose stays queryable in first-class columns.
CREATE TABLE card_profile_v5 (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  archetype TEXT NOT NULL CHECK (archetype IN (
    'concept', 'comparison', 'process', 'enumeration', 'scale_mapping',
    'formula_rule', 'diagram', 'cloze', 'qa', 'math_error')),
  knowledge_point TEXT NOT NULL DEFAULT '',
  source_type TEXT NOT NULL DEFAULT 'notes' CHECK (source_type IN (
    'textbook', 'course', 'past_exam', 'practice', 'notes', 'other')),
  source_title TEXT NOT NULL DEFAULT '',
  source_chapter TEXT NOT NULL DEFAULT '',
  source_locator TEXT NOT NULL DEFAULT '',
  source_year INTEGER CHECK (source_year IS NULL OR source_year BETWEEN 1900 AND 2200),
  mechanism_markdown TEXT NOT NULL DEFAULT '',
  conditions_markdown TEXT NOT NULL DEFAULT '',
  contrast_markdown TEXT NOT NULL DEFAULT '',
  example_markdown TEXT NOT NULL DEFAULT '',
  common_trap_markdown TEXT NOT NULL DEFAULT '',
  transfer_prompt_markdown TEXT NOT NULL DEFAULT '',
  mnemonic TEXT NOT NULL DEFAULT '',
  first_attempt_markdown TEXT NOT NULL DEFAULT '',
  error_trigger_markdown TEXT NOT NULL DEFAULT '',
  general_method_markdown TEXT NOT NULL DEFAULT '',
  verification_markdown TEXT NOT NULL DEFAULT '',
  target_seconds INTEGER CHECK (target_seconds IS NULL OR target_seconds BETWEEN 10 AND 7200),
  structured_payload_json TEXT NOT NULL DEFAULT '{}'
    CHECK (json_valid(structured_payload_json) AND json_type(structured_payload_json) IN ('object', 'array')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  CHECK (archetype = 'math_error' OR target_seconds IS NULL)
);
CREATE INDEX card_profile_v5_knowledge_idx ON card_profile_v5 (knowledge_point, source_title);

-- Existing content receives a conservative profile without changing its
-- prompt, answer, due date, or historical schedule.
INSERT INTO card_profile_v5 (
  study_item_id, archetype, source_type, source_title, source_locator, source_year,
  first_attempt_markdown, created_at, updated_at
)
SELECT s.id,
       CASE
         WHEN s.kind = 'math_problem' THEN 'math_error'
         WHEN m.template_type = 'comparison' THEN 'comparison'
         WHEN m.template_type = 'enumeration' THEN 'enumeration'
         WHEN m.template_type = 'image_occlusion' THEN 'diagram'
         WHEN m.template_type = 'cloze' THEN 'cloze'
         ELSE 'qa'
       END,
       CASE WHEN s.kind = 'math_problem' THEN 'practice' ELSE 'notes' END,
       COALESCE(p.source_name, ''),
       trim(COALESCE(p.source_page, '') ||
         CASE WHEN COALESCE(p.source_page, '') <> '' AND COALESCE(p.source_problem_number, '') <> ''
              THEN ' · ' ELSE '' END || COALESCE(p.source_problem_number, '')),
       p.source_year, COALESCE(p.wrong_step_markdown, ''), s.created_at, s.updated_at
FROM study_item s
LEFT JOIN memory_card m ON m.study_item_id = s.id
LEFT JOIN math_problem p ON p.study_item_id = s.id;

CREATE TRIGGER card_profile_v5_after_memory_insert
AFTER INSERT ON memory_card
BEGIN
  INSERT OR IGNORE INTO card_profile_v5 (
    study_item_id, archetype, source_type, created_at, updated_at
  ) SELECT NEW.study_item_id,
      CASE NEW.template_type
        WHEN 'comparison' THEN 'comparison'
        WHEN 'enumeration' THEN 'enumeration'
        WHEN 'image_occlusion' THEN 'diagram'
        WHEN 'cloze' THEN 'cloze'
        ELSE 'qa'
      END,
      'notes', s.created_at, s.updated_at
    FROM study_item s WHERE s.id = NEW.study_item_id;
END;

CREATE TRIGGER card_profile_v5_after_math_insert
AFTER INSERT ON math_problem
BEGIN
  INSERT OR IGNORE INTO card_profile_v5 (
    study_item_id, archetype, source_type, source_title, created_at, updated_at
  ) SELECT NEW.study_item_id, 'math_error', 'practice', COALESCE(NEW.source_name, ''),
      s.created_at, s.updated_at
    FROM study_item s WHERE s.id = NEW.study_item_id;
END;

CREATE TABLE learning_task_v5 (
  id TEXT PRIMARY KEY,
  learning_unit_id TEXT NOT NULL REFERENCES learning_unit_v5(id),
  source_study_item_id TEXT REFERENCES study_item(id),
  task_type TEXT NOT NULL CHECK (task_type IN ('math_repair', 'math_original', 'math_variant',
    'math_transfer', 'math_retention', 'memory_recall', 'memory_explain', 'memory_compare',
    'memory_diagram', 'memory_calculate')),
  task_state TEXT NOT NULL DEFAULT 'active' CHECK (task_state IN (
    'active', 'blocked', 'awaiting_variant', 'graduated', 'legacy', 'archived')),
  math_phase TEXT CHECK (math_phase IS NULL OR math_phase IN (
    'repair', 'original', 'variant', 'transfer', 'retention', 'awaiting_variant', 'graduated')),
  due_at INTEGER NOT NULL DEFAULT 0,
  legacy_due_at INTEGER NOT NULL DEFAULT 0,
  last_reviewed_at INTEGER NOT NULL DEFAULT 0,
  repetitions INTEGER NOT NULL DEFAULT 0 CHECK (repetitions >= 0),
  consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  estimated_seconds INTEGER NOT NULL DEFAULT 60 CHECK (estimated_seconds BETWEEN 1 AND 7200),
  dependency_ready INTEGER NOT NULL DEFAULT 1 CHECK (dependency_ready IN (0, 1)),
  source_generation INTEGER NOT NULL DEFAULT 5 CHECK (source_generation BETWEEN 1 AND 5),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX learning_task_v5_plan_idx ON learning_task_v5
  (task_state, dependency_ready, due_at, learning_unit_id);

CREATE TABLE learning_unit_relation_v5 (
  source_unit_id TEXT NOT NULL REFERENCES learning_unit_v5(id),
  target_unit_id TEXT NOT NULL REFERENCES learning_unit_v5(id),
  relation_type TEXT NOT NULL CHECK (relation_type IN ('prerequisite', 'confusable', 'similar', 'related')),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (source_unit_id, target_unit_id, relation_type),
  CHECK (source_unit_id <> target_unit_id)
) WITHOUT ROWID;

-- Evidence is the portable append-only source of truth.  Task state above is
-- a replayable projection/cache and may never be used to overwrite old due
-- dates before the first v5 fact.
CREATE TABLE learning_evidence_v5 (
  evidence_id TEXT PRIMARY KEY,
  learning_task_id TEXT NOT NULL REFERENCES learning_task_v5(id),
  task_type TEXT NOT NULL,
  reviewed_at INTEGER NOT NULL,
  correct INTEGER NOT NULL CHECK (correct IN (0, 1)),
  error_mask INTEGER NOT NULL DEFAULT 0 CHECK (error_mask BETWEEN 0 AND 127),
  point_hits INTEGER CHECK (point_hits IS NULL OR point_hits >= 0),
  point_count INTEGER CHECK (point_count IS NULL OR point_count > 0),
  hint_level INTEGER NOT NULL DEFAULT 0 CHECK (hint_level BETWEEN 0 AND 9),
  answer_revealed INTEGER NOT NULL DEFAULT 0 CHECK (answer_revealed IN (0, 1)),
  duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  duration_reliable INTEGER NOT NULL DEFAULT 1 CHECK (duration_reliable IN (0, 1)),
  confidence INTEGER CHECK (confidence IS NULL OR confidence BETWEEN 1 AND 5),
  reflection_markdown TEXT NOT NULL DEFAULT '',
  artifact_id TEXT REFERENCES attempt_artifact(id),
  device_id TEXT NOT NULL,
  device_counter INTEGER NOT NULL CHECK (device_counter > 0),
  causal_cursor INTEGER NOT NULL DEFAULT 0 CHECK (causal_cursor >= 0),
  created_at INTEGER NOT NULL,
  UNIQUE (device_id, device_counter),
  CHECK (point_count IS NULL OR point_hits IS NOT NULL AND point_hits <= point_count)
);
CREATE INDEX learning_evidence_v5_replay_idx ON learning_evidence_v5
  (learning_task_id, causal_cursor, reviewed_at, evidence_id);
CREATE TRIGGER learning_evidence_v5_no_update BEFORE UPDATE ON learning_evidence_v5
BEGIN SELECT RAISE(ABORT, 'learning_evidence_v5 is immutable'); END;
CREATE TRIGGER learning_evidence_v5_no_delete BEFORE DELETE ON learning_evidence_v5
BEGIN SELECT RAISE(ABORT, 'learning_evidence_v5 is immutable'); END;

-- Keep task routes consistent across SQLite clients as new content arrives.
CREATE TRIGGER learning_v5_route_after_study_item_insert
AFTER INSERT ON study_item
WHEN NEW.deleted_at IS NULL
BEGIN
  INSERT OR IGNORE INTO learning_unit_v5 (
    id, source_study_item_id, chapter_id, subject, unit_type, created_at, updated_at
  ) VALUES (
    'v5-unit:' || NEW.id, NEW.id, NEW.chapter_id, NEW.subject,
    CASE WHEN NEW.kind = 'math_problem' THEN 'math_error_cluster' ELSE 'memory_knowledge_package' END,
    NEW.created_at, NEW.updated_at
  );
  INSERT OR IGNORE INTO learning_task_v5 (
    id, learning_unit_id, source_study_item_id, task_type, task_state,
    math_phase, due_at, legacy_due_at, last_reviewed_at, repetitions, created_at, updated_at
  ) SELECT
    'v5-task:' || NEW.id, 'v5-unit:' || NEW.id, NEW.id,
    'memory_recall', 'active', NULL,
    NEW.due_at, 0, NEW.last_reviewed_at, NEW.repetitions, NEW.created_at, NEW.updated_at
  WHERE NEW.kind <> 'math_problem';
  INSERT OR IGNORE INTO learning_task_v5 (
    id, learning_unit_id, source_study_item_id, task_type, task_state,
    math_phase, due_at, legacy_due_at, last_reviewed_at, repetitions,
    estimated_seconds, created_at, updated_at
  )
  SELECT 'v5-task:' || NEW.id || ':repair', 'v5-unit:' || NEW.id, NEW.id,
    'math_repair', 'active', 'repair', NEW.due_at, 0, NEW.last_reviewed_at,
    NEW.repetitions, 480, NEW.created_at, NEW.updated_at
  WHERE NEW.kind = 'math_problem';
END;

CREATE TABLE learning_personalization_v5 (
  scope_key TEXT PRIMARY KEY,
  sample_count INTEGER NOT NULL DEFAULT 0 CHECK (sample_count >= 0),
  success_count INTEGER NOT NULL DEFAULT 0 CHECK (success_count >= 0),
  mean_reliable_seconds REAL NOT NULL DEFAULT 0 CHECK (mean_reliable_seconds >= 0),
  interval_multiplier REAL NOT NULL DEFAULT 1.0 CHECK (interval_multiplier BETWEEN 0.75 AND 1.25),
  updated_at INTEGER NOT NULL,
  CHECK (success_count <= sample_count)
);

-- Migration creates exactly one conservative task per source item. Existing
-- due_at is copied, never rescheduled. A v5 evidence event opts it into the
-- phase route; new package editors create the additional explanation tasks.
INSERT INTO learning_unit_v5 (id, unit_type, source_study_item_id, subject, chapter_id, title, created_at, updated_at)
SELECT 'v5-unit:' || id,
       CASE kind WHEN 'math_problem' THEN 'math_error_cluster' ELSE 'memory_knowledge_package' END,
       id, subject, chapter_id, '', created_at, updated_at
FROM study_item WHERE deleted_at IS NULL;

INSERT INTO learning_task_v5 (id, learning_unit_id, source_study_item_id, task_type, task_state,
  math_phase, due_at, legacy_due_at, last_reviewed_at, repetitions, estimated_seconds,
  source_generation, created_at, updated_at)
SELECT 'v5-task:' || id, 'v5-unit:' || id, id,
       CASE kind WHEN 'math_problem' THEN 'math_original' ELSE 'memory_recall' END,
       'legacy', CASE kind WHEN 'math_problem' THEN 'original' ELSE NULL END,
       CASE WHEN COALESCE(c.due_at, 0) > 0 THEN c.due_at ELSE s.due_at END,
       CASE WHEN COALESCE(c.due_at, 0) > 0 THEN c.due_at ELSE s.due_at END, s.last_reviewed_at,
       repetitions, CASE kind WHEN 'math_problem' THEN 480 ELSE 60 END,
       4, created_at, updated_at
FROM study_item s LEFT JOIN schedule_cache_v4 c ON c.study_item_id = s.id
WHERE s.deleted_at IS NULL;

-- Evidence changes are exported as immutable facts when a device identity is
-- present.  Remote projection uses the same entity type and deterministic replay.
CREATE TRIGGER sync_learning_evidence_v5_insert AFTER INSERT ON learning_evidence_v5
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at)
  SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
    substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    0, 'learningEvidence', NEW.evidence_id, 'create',
    json_object('taskId', NEW.learning_task_id, 'taskType', NEW.task_type,
      'reviewedAt', NEW.reviewed_at, 'correct', NEW.correct, 'errorMask', NEW.error_mask,
      'pointHits', NEW.point_hits, 'pointCount', NEW.point_count, 'hintLevel', NEW.hint_level,
      'answerRevealed', NEW.answer_revealed, 'durationSeconds', NEW.duration_seconds,
      'durationReliable', NEW.duration_reliable, 'confidence', NEW.confidence,
      'reflection', NEW.reflection_markdown, 'artifactId', NEW.artifact_id,
      'causalCursor', NEW.causal_cursor), NEW.created_at
  FROM local_device WHERE singleton = 1;
END;

-- Card profiles are portable content. Keeping them as one entity prevents a
-- partially synchronized source or diagnosis from changing the card's meaning.
CREATE TRIGGER sync_card_profile_v5_insert AFTER INSERT ON card_profile_v5
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at)
  SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
    substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    0, 'cardProfile', NEW.study_item_id, 'create',
    json_object('archetype', NEW.archetype, 'knowledgePoint', NEW.knowledge_point,
      'sourceType', NEW.source_type, 'sourceTitle', NEW.source_title,
      'sourceChapter', NEW.source_chapter, 'sourceLocator', NEW.source_locator,
      'sourceYear', NEW.source_year, 'mechanism', NEW.mechanism_markdown,
      'conditions', NEW.conditions_markdown, 'contrast', NEW.contrast_markdown,
      'example', NEW.example_markdown, 'commonTrap', NEW.common_trap_markdown,
      'transferPrompt', NEW.transfer_prompt_markdown, 'mnemonic', NEW.mnemonic,
      'firstAttempt', NEW.first_attempt_markdown, 'errorTrigger', NEW.error_trigger_markdown,
      'generalMethod', NEW.general_method_markdown, 'verification', NEW.verification_markdown,
      'targetSeconds', NEW.target_seconds, 'structuredPayload', json(NEW.structured_payload_json)),
    NEW.created_at FROM local_device WHERE singleton = 1;
END;

CREATE TRIGGER sync_card_profile_v5_update AFTER UPDATE ON card_profile_v5
WHEN EXISTS (SELECT 1 FROM local_device WHERE singleton = 1)
BEGIN
  UPDATE local_device SET next_counter = next_counter + 1 WHERE singleton = 1;
  INSERT INTO sync_outbox (operation_id, device_id, device_counter, base_cursor, base_revision,
    entity_type, entity_id, action, changed_fields_json, occurred_at)
  SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
    substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
    device_id, next_counter - 1,
    COALESCE((SELECT server_seq FROM sync_cursor WHERE workspace_id = local_device.workspace_id), 0),
    COALESCE((SELECT revision FROM sync_revision WHERE entity_type = 'cardProfile'
      AND entity_id = NEW.study_item_id), 0),
    'cardProfile', NEW.study_item_id, 'update',
    json_object('archetype', NEW.archetype, 'knowledgePoint', NEW.knowledge_point,
      'sourceType', NEW.source_type, 'sourceTitle', NEW.source_title,
      'sourceChapter', NEW.source_chapter, 'sourceLocator', NEW.source_locator,
      'sourceYear', NEW.source_year, 'mechanism', NEW.mechanism_markdown,
      'conditions', NEW.conditions_markdown, 'contrast', NEW.contrast_markdown,
      'example', NEW.example_markdown, 'commonTrap', NEW.common_trap_markdown,
      'transferPrompt', NEW.transfer_prompt_markdown, 'mnemonic', NEW.mnemonic,
      'firstAttempt', NEW.first_attempt_markdown, 'errorTrigger', NEW.error_trigger_markdown,
      'generalMethod', NEW.general_method_markdown, 'verification', NEW.verification_markdown,
      'targetSeconds', NEW.target_seconds, 'structuredPayload', json(NEW.structured_payload_json)),
    NEW.updated_at FROM local_device WHERE singleton = 1;
END;

UPDATE schema_metadata SET schema_version = 5 WHERE singleton = 1;
PRAGMA user_version = 5;

COMMIT;
