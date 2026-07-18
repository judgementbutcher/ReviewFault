PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

ALTER TABLE learning_preferences ADD COLUMN scheduler_generation INTEGER NOT NULL DEFAULT 3
  CHECK (scheduler_generation IN (2, 3));

ALTER TABLE schedule_state_v2 ADD COLUMN active_algorithm_version INTEGER NOT NULL DEFAULT 2
  CHECK (active_algorithm_version IN (2, 3));
ALTER TABLE schedule_state_v2 ADD COLUMN active_parameter_version INTEGER NOT NULL DEFAULT 1
  CHECK (active_parameter_version > 0);

CREATE TABLE algorithm_parameter_registry (
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  algorithm_version INTEGER NOT NULL CHECK (algorithm_version IN (2, 3)),
  parameter_version INTEGER NOT NULL CHECK (parameter_version > 0),
  checksum TEXT NOT NULL CHECK (length(checksum) = 64 AND checksum GLOB '[0-9a-f]*'),
  effective_at INTEGER NOT NULL,
  personalized INTEGER NOT NULL DEFAULT 0 CHECK (personalized IN (0, 1)),
  PRIMARY KEY (algorithm, algorithm_version, parameter_version),
  UNIQUE (algorithm, algorithm_version, parameter_version, checksum)
) WITHOUT ROWID;

INSERT INTO algorithm_parameter_registry VALUES
  ('memory_fsrs_6', 2, 1, 'f2a6d3a7fd8c090ea30b71b112116a1f09f778c8f84dd74831b063034e6f1001', 1704067200, 0),
  ('math_mastery_ladder', 2, 1, '92d0508b20bbda71b14c8e5a6df2c13599a467395f3ecf3b6095adbb18f7a34b', 1704067200, 0),
  ('memory_fsrs_6', 3, 2, 'bd98e3fdf07a9223a39b5305fe5c14e8d9a03013ddbbce3f5d9ea15555c9c177', 1784304000, 0),
  ('memory_fsrs_6', 3, 3, '083f217e835490d1760ee5bfc94693b1b4fb827e3ed121cbd970f401d6271019', 1784304000, 1),
  ('math_mastery_ladder', 3, 2, '229003e5c13709bb8af1443b1d4585a025dc92db742520a82740d01a4fe9c089', 1784304000, 0);

CREATE TABLE review_event_v3 (
  id TEXT PRIMARY KEY,
  study_item_id TEXT NOT NULL REFERENCES study_item(id),
  algorithm TEXT NOT NULL CHECK (algorithm IN ('memory_fsrs_6', 'math_mastery_ladder')),
  algorithm_version INTEGER NOT NULL CHECK (algorithm_version = 3),
  parameter_version INTEGER NOT NULL CHECK (parameter_version > 0),
  parameter_checksum TEXT NOT NULL CHECK (length(parameter_checksum) = 64),
  preference TEXT NOT NULL CHECK (preference IN (
    'time_saving', 'balanced', 'reinforced', 'intensive', 'relaxed'
  )),
  feedback INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL,
  duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  duration_quality TEXT NOT NULL CHECK (duration_quality IN (
    'unknown', 'reliable', 'too_short', 'interrupted'
  )),
  client_timezone_offset_minutes INTEGER NOT NULL
    CHECK (client_timezone_offset_minutes BETWEEN -840 AND 840),
  due_at_before INTEGER NOT NULL,
  due_at_after INTEGER NOT NULL,
  decision_flags INTEGER NOT NULL DEFAULT 0 CHECK (decision_flags >= 0),
  decision_snapshot_json TEXT NOT NULL CHECK (json_valid(decision_snapshot_json)),
  device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (algorithm, algorithm_version, parameter_version, parameter_checksum)
    REFERENCES algorithm_parameter_registry(
      algorithm, algorithm_version, parameter_version, checksum
    ),
  CHECK (
    (algorithm = 'memory_fsrs_6' AND feedback BETWEEN 1 AND 4) OR
    (algorithm = 'math_mastery_ladder' AND feedback BETWEEN 0 AND 3)
  )
);

CREATE INDEX review_event_v3_item_time_idx
  ON review_event_v3 (study_item_id, reviewed_at DESC);

CREATE TABLE memory_review_event_v3 (
  review_event_id TEXT PRIMARY KEY REFERENCES review_event_v3(id),
  state_before INTEGER NOT NULL CHECK (state_before BETWEEN 0 AND 3),
  state_after INTEGER NOT NULL CHECK (state_after BETWEEN 0 AND 3),
  target_retention REAL NOT NULL CHECK (target_retention BETWEEN 0.80 AND 0.99),
  elapsed_days REAL NOT NULL CHECK (elapsed_days >= 0),
  scheduled_days REAL NOT NULL CHECK (scheduled_days > 0 AND scheduled_days <= 3650),
  retrievability_before REAL NOT NULL CHECK (retrievability_before BETWEEN 0 AND 1),
  difficulty_before REAL NOT NULL CHECK (difficulty_before BETWEEN 0 AND 10),
  difficulty_after REAL NOT NULL CHECK (difficulty_after BETWEEN 1 AND 10),
  stability_before REAL NOT NULL CHECK (stability_before >= 0),
  stability_after REAL NOT NULL CHECK (stability_after > 0),
  personalized INTEGER NOT NULL CHECK (personalized IN (0, 1)),
  learning_step INTEGER NOT NULL CHECK (learning_step IN (0, 1)),
  overdue_days REAL NOT NULL CHECK (overdue_days >= 0)
);

CREATE TABLE math_review_event_v3 (
  review_event_id TEXT PRIMARY KEY REFERENCES review_event_v3(id),
  attempt_id TEXT UNIQUE REFERENCES attempt(id),
  requested_feedback INTEGER NOT NULL CHECK (requested_feedback BETWEEN 0 AND 3),
  applied_feedback INTEGER NOT NULL CHECK (applied_feedback BETWEEN 0 AND 3),
  error_reason TEXT CHECK (error_reason IS NULL OR error_reason IN (
    'concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'
  )),
  hint_revealed INTEGER NOT NULL CHECK (hint_revealed IN (0, 1)),
  mastery_before INTEGER NOT NULL CHECK (mastery_before BETWEEN 0 AND 6),
  mastery_after INTEGER NOT NULL CHECK (mastery_after BETWEEN 0 AND 6),
  fluent_streak_before INTEGER NOT NULL CHECK (fluent_streak_before >= 0),
  fluent_streak_after INTEGER NOT NULL CHECK (fluent_streak_after >= 0),
  consecutive_failures INTEGER NOT NULL CHECK (consecutive_failures >= 0),
  scheduled_days REAL NOT NULL CHECK (scheduled_days BETWEEN 0.5 AND 365),
  CHECK (hint_revealed = 0 OR applied_feedback <= 2)
);

CREATE TRIGGER review_event_v3_no_update
BEFORE UPDATE ON review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'review_event_v3 is immutable');
END;
CREATE TRIGGER review_event_v3_no_delete
BEFORE DELETE ON review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'review_event_v3 is immutable');
END;
CREATE TRIGGER memory_review_event_v3_no_update
BEFORE UPDATE ON memory_review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'memory_review_event_v3 is immutable');
END;
CREATE TRIGGER memory_review_event_v3_no_delete
BEFORE DELETE ON memory_review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'memory_review_event_v3 is immutable');
END;
CREATE TRIGGER math_review_event_v3_no_update
BEFORE UPDATE ON math_review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'math_review_event_v3 is immutable');
END;
CREATE TRIGGER math_review_event_v3_no_delete
BEFORE DELETE ON math_review_event_v3
BEGIN
  SELECT RAISE(ABORT, 'math_review_event_v3 is immutable');
END;

UPDATE schema_metadata SET schema_version = 3 WHERE singleton = 1;
PRAGMA user_version = 3;

COMMIT;
