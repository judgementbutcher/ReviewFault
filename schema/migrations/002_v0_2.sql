PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

-- Preferences in this table are portable study behavior. Theme, notification
-- permission and reminder time remain device-local platform preferences.
CREATE TABLE IF NOT EXISTS learning_preferences (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  daily_new_memory_limit INTEGER NOT NULL DEFAULT 20
    CHECK (daily_new_memory_limit BETWEEN 0 AND 500),
  session_minutes INTEGER NOT NULL DEFAULT 20
    CHECK (session_minutes BETWEEN 1 AND 240),
  enable_data_structures INTEGER NOT NULL DEFAULT 1 CHECK (enable_data_structures IN (0, 1)),
  enable_computer_organization INTEGER NOT NULL DEFAULT 1 CHECK (enable_computer_organization IN (0, 1)),
  enable_operating_systems INTEGER NOT NULL DEFAULT 1 CHECK (enable_operating_systems IN (0, 1)),
  enable_computer_networks INTEGER NOT NULL DEFAULT 1 CHECK (enable_computer_networks IN (0, 1)),
  include_memory_cards INTEGER NOT NULL DEFAULT 1 CHECK (include_memory_cards IN (0, 1)),
  include_math_problems INTEGER NOT NULL DEFAULT 1 CHECK (include_math_problems IN (0, 1)),
  memory_preset TEXT NOT NULL DEFAULT 'balanced'
    CHECK (memory_preset IN ('time_saving', 'balanced', 'reinforced')),
  math_intensity TEXT NOT NULL DEFAULT 'balanced'
    CHECK (math_intensity IN ('intensive', 'balanced', 'relaxed')),
  updated_at INTEGER NOT NULL
);

INSERT OR IGNORE INTO learning_preferences (singleton, updated_at)
VALUES (1, CAST(strftime('%s', 'now') AS INTEGER));

-- This common state is used to select queues without interpreting the typed
-- algorithm payload. Existing due_at is copied verbatim during migration.
CREATE TABLE IF NOT EXISTS schedule_state_v2 (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  algorithm_version INTEGER NOT NULL DEFAULT 2 CHECK (algorithm_version = 2),
  parameter_version INTEGER NOT NULL DEFAULT 1 CHECK (parameter_version > 0),
  due_at INTEGER NOT NULL DEFAULT 0,
  last_reviewed_at INTEGER NOT NULL DEFAULT 0,
  repetitions INTEGER NOT NULL DEFAULT 0 CHECK (repetitions >= 0),
  needs_history_replay INTEGER NOT NULL DEFAULT 0 CHECK (needs_history_replay IN (0, 1)),
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS schedule_state_v2_due_idx
  ON schedule_state_v2 (due_at, algorithm)
  WHERE due_at > 0;

CREATE TABLE IF NOT EXISTS memory_schedule_state (
  study_item_id TEXT PRIMARY KEY REFERENCES schedule_state_v2(study_item_id),
  state INTEGER NOT NULL DEFAULT 0 CHECK (state BETWEEN 0 AND 3),
  difficulty REAL NOT NULL DEFAULT 0 CHECK (difficulty BETWEEN 0 AND 10),
  stability_days REAL NOT NULL DEFAULT 0 CHECK (stability_days >= 0),
  lapses INTEGER NOT NULL DEFAULT 0 CHECK (lapses >= 0),
  CHECK (
    (state = 0 AND difficulty = 0 AND stability_days = 0) OR
    (state <> 0 AND difficulty BETWEEN 1 AND 10 AND stability_days > 0)
  )
);

CREATE TABLE IF NOT EXISTS math_schedule_state (
  study_item_id TEXT PRIMARY KEY REFERENCES schedule_state_v2(study_item_id),
  mastery_level INTEGER NOT NULL DEFAULT 0 CHECK (mastery_level BETWEEN 0 AND 6),
  fluent_streak INTEGER NOT NULL DEFAULT 0 CHECK (fluent_streak >= 0)
);

CREATE TRIGGER IF NOT EXISTS study_item_memory_schedule_v2_insert
AFTER INSERT ON study_item
WHEN NEW.kind = 'memory_card'
BEGIN
  INSERT INTO schedule_state_v2 (
    study_item_id, algorithm, due_at, last_reviewed_at, repetitions,
    needs_history_replay, updated_at
  ) VALUES (NEW.id, 'memory_fsrs_6', NEW.due_at, NEW.last_reviewed_at,
            NEW.repetitions, 0, NEW.updated_at);
  INSERT INTO memory_schedule_state (study_item_id) VALUES (NEW.id);
END;

CREATE TRIGGER IF NOT EXISTS study_item_math_schedule_v2_insert
AFTER INSERT ON study_item
WHEN NEW.kind = 'math_problem'
BEGIN
  INSERT INTO schedule_state_v2 (
    study_item_id, algorithm, due_at, last_reviewed_at, repetitions,
    needs_history_replay, updated_at
  ) VALUES (NEW.id, 'math_mastery_ladder', NEW.due_at, NEW.last_reviewed_at,
            NEW.repetitions, 0, NEW.updated_at);
  INSERT INTO math_schedule_state (study_item_id) VALUES (NEW.id);
END;

-- The common event and its typed detail rows form one immutable audit record.
CREATE TABLE IF NOT EXISTS review_event_v2 (
  id TEXT PRIMARY KEY,
  study_item_id TEXT NOT NULL REFERENCES study_item(id),
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  algorithm_version INTEGER NOT NULL CHECK (algorithm_version = 2),
  parameter_version INTEGER NOT NULL CHECK (parameter_version > 0),
  preference TEXT NOT NULL CHECK (preference IN (
    'time_saving', 'balanced', 'reinforced', 'intensive', 'relaxed'
  )),
  feedback INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL,
  duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  due_at_before INTEGER NOT NULL,
  due_at_after INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  migrated_from_v1 INTEGER NOT NULL DEFAULT 0 CHECK (migrated_from_v1 IN (0, 1)),
  created_at INTEGER NOT NULL,
  CHECK (
    (algorithm = 'memory_fsrs_6' AND feedback BETWEEN 1 AND 4) OR
    (algorithm = 'math_mastery_ladder' AND feedback BETWEEN 0 AND 3)
  )
);

CREATE INDEX IF NOT EXISTS review_event_v2_item_time_idx
  ON review_event_v2 (study_item_id, reviewed_at DESC);

CREATE TABLE IF NOT EXISTS memory_review_event_v2 (
  review_event_id TEXT PRIMARY KEY REFERENCES review_event_v2(id),
  state_before INTEGER NOT NULL CHECK (state_before BETWEEN 0 AND 3),
  state_after INTEGER NOT NULL CHECK (state_after BETWEEN 0 AND 3),
  target_retention REAL NOT NULL CHECK (target_retention IN (0.85, 0.90, 0.93)),
  elapsed_days REAL NOT NULL CHECK (elapsed_days >= 0),
  scheduled_days REAL NOT NULL CHECK (scheduled_days > 0),
  retrievability_before REAL NOT NULL CHECK (retrievability_before BETWEEN 0 AND 1),
  difficulty_before REAL NOT NULL CHECK (difficulty_before BETWEEN 0 AND 10),
  difficulty_after REAL NOT NULL CHECK (difficulty_after BETWEEN 1 AND 10),
  stability_before REAL NOT NULL CHECK (stability_before >= 0),
  stability_after REAL NOT NULL CHECK (stability_after > 0)
);

CREATE TABLE IF NOT EXISTS math_review_event_v2 (
  review_event_id TEXT PRIMARY KEY REFERENCES review_event_v2(id),
  attempt_id TEXT UNIQUE REFERENCES attempt(id),
  requested_feedback INTEGER NOT NULL CHECK (requested_feedback BETWEEN 0 AND 3),
  applied_feedback INTEGER NOT NULL CHECK (applied_feedback BETWEEN 0 AND 3),
  error_reason TEXT CHECK (error_reason IS NULL OR error_reason IN (
    'concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'
  )),
  hint_revealed INTEGER NOT NULL DEFAULT 0 CHECK (hint_revealed IN (0, 1)),
  mastery_before INTEGER NOT NULL CHECK (mastery_before BETWEEN 0 AND 6),
  mastery_after INTEGER NOT NULL CHECK (mastery_after BETWEEN 0 AND 6),
  fluent_streak_before INTEGER NOT NULL CHECK (fluent_streak_before >= 0),
  fluent_streak_after INTEGER NOT NULL CHECK (fluent_streak_after >= 0),
  scheduled_days REAL NOT NULL CHECK (scheduled_days BETWEEN 1 AND 180),
  CHECK (hint_revealed = 0 OR applied_feedback <= 2)
);

CREATE TRIGGER IF NOT EXISTS review_event_v2_no_update
BEFORE UPDATE ON review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'review_event_v2 is immutable');
END;

CREATE TRIGGER IF NOT EXISTS review_event_v2_no_delete
BEFORE DELETE ON review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'review_event_v2 is immutable');
END;

CREATE TRIGGER IF NOT EXISTS memory_review_event_v2_no_update
BEFORE UPDATE ON memory_review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'memory_review_event_v2 is immutable');
END;

CREATE TRIGGER IF NOT EXISTS memory_review_event_v2_no_delete
BEFORE DELETE ON memory_review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'memory_review_event_v2 is immutable');
END;

CREATE TRIGGER IF NOT EXISTS math_review_event_v2_no_update
BEFORE UPDATE ON math_review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'math_review_event_v2 is immutable');
END;

CREATE TRIGGER IF NOT EXISTS math_review_event_v2_no_delete
BEFORE DELETE ON math_review_event_v2
BEGIN
  SELECT RAISE(ABORT, 'math_review_event_v2 is immutable');
END;

CREATE INDEX IF NOT EXISTS study_item_library_v2_idx
  ON study_item (subject, kind, updated_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS study_item_trash_v2_idx
  ON study_item (deleted_at DESC)
  WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS study_item_tag_tag_v2_idx
  ON study_item_tag (tag_id, study_item_id);

INSERT OR IGNORE INTO schedule_state_v2 (
  study_item_id, algorithm, due_at, last_reviewed_at, repetitions,
  needs_history_replay, updated_at
)
SELECT id,
       CASE kind WHEN 'memory_card' THEN 'memory_fsrs_6' ELSE 'math_mastery_ladder' END,
       due_at,
       last_reviewed_at,
       repetitions,
       CASE WHEN repetitions > 0 THEN 1 ELSE 0 END,
       updated_at
FROM study_item;

INSERT OR IGNORE INTO memory_schedule_state (
  study_item_id, state, difficulty, stability_days, lapses
)
SELECT id, scheduler_state, difficulty, stability_days, lapses
FROM study_item
WHERE kind = 'memory_card';

INSERT OR IGNORE INTO math_schedule_state (study_item_id, mastery_level, fluent_streak)
SELECT id, 0, 0
FROM study_item
WHERE kind = 'math_problem';

UPDATE schema_metadata SET schema_version = 2 WHERE singleton = 1;
PRAGMA user_version = 2;

COMMIT;
