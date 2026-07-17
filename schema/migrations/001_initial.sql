PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

CREATE TABLE schema_metadata (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  schema_version INTEGER NOT NULL CHECK (schema_version > 0),
  created_at INTEGER NOT NULL
);

INSERT INTO schema_metadata (singleton, schema_version, created_at)
VALUES (1, 1, CAST(strftime('%s', 'now') AS INTEGER));

CREATE TABLE chapter (
  id TEXT PRIMARY KEY,
  subject TEXT NOT NULL CHECK (subject IN (
    'math',
    'data_structures',
    'computer_organization',
    'operating_systems',
    'computer_networks'
  )),
  parent_id TEXT REFERENCES chapter(id),
  name TEXT NOT NULL CHECK (length(trim(name)) > 0),
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE study_item (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('math_problem', 'memory_card')),
  subject TEXT NOT NULL CHECK (subject IN (
    'math',
    'data_structures',
    'computer_organization',
    'operating_systems',
    'computer_networks'
  )),
  chapter_id TEXT REFERENCES chapter(id),
  scheduler_abi_version INTEGER NOT NULL DEFAULT 1 CHECK (scheduler_abi_version > 0),
  scheduler_state INTEGER NOT NULL DEFAULT 0 CHECK (scheduler_state BETWEEN 0 AND 3),
  difficulty REAL NOT NULL DEFAULT 0 CHECK (difficulty BETWEEN 0 AND 10),
  stability_days REAL NOT NULL DEFAULT 0 CHECK (stability_days >= 0),
  due_at INTEGER NOT NULL DEFAULT 0,
  last_reviewed_at INTEGER NOT NULL DEFAULT 0,
  repetitions INTEGER NOT NULL DEFAULT 0 CHECK (repetitions >= 0),
  lapses INTEGER NOT NULL DEFAULT 0 CHECK (lapses >= 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  suspended_at INTEGER,
  deleted_at INTEGER,
  CHECK (
    (scheduler_state = 0 AND difficulty = 0 AND stability_days = 0) OR
    (scheduler_state <> 0 AND difficulty BETWEEN 1 AND 10 AND stability_days > 0)
  )
);

CREATE INDEX study_item_due_idx
  ON study_item (due_at, subject)
  WHERE suspended_at IS NULL AND deleted_at IS NULL AND scheduler_state <> 0;
CREATE INDEX study_item_chapter_idx ON study_item (chapter_id);

CREATE TABLE math_problem (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  source_name TEXT,
  source_page TEXT,
  source_problem_number TEXT,
  source_year INTEGER,
  prompt_markdown TEXT NOT NULL DEFAULT '',
  solution_markdown TEXT NOT NULL DEFAULT '',
  wrong_step_markdown TEXT NOT NULL DEFAULT '',
  key_hint_markdown TEXT NOT NULL DEFAULT '',
  default_error_reason TEXT CHECK (default_error_reason IS NULL OR default_error_reason IN (
    'concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'
  ))
);

CREATE TRIGGER math_problem_kind_guard
BEFORE INSERT ON math_problem
WHEN COALESCE((SELECT kind FROM study_item WHERE id = NEW.study_item_id), '') <> 'math_problem'
BEGIN
  SELECT RAISE(ABORT, 'math_problem requires a math_problem study_item');
END;

CREATE TABLE memory_card (
  study_item_id TEXT PRIMARY KEY REFERENCES study_item(id),
  template_type TEXT NOT NULL CHECK (template_type IN (
    'qa', 'cloze', 'layered_hint', 'enumeration', 'image_occlusion', 'comparison'
  )),
  prompt_markdown TEXT NOT NULL CHECK (length(trim(prompt_markdown)) > 0),
  answer_markdown TEXT NOT NULL DEFAULT '',
  hints_json TEXT NOT NULL DEFAULT '[]',
  answer_points_json TEXT NOT NULL DEFAULT '[]',
  occlusions_json TEXT NOT NULL DEFAULT '[]'
);

CREATE TRIGGER memory_card_kind_guard
BEFORE INSERT ON memory_card
WHEN COALESCE((SELECT kind FROM study_item WHERE id = NEW.study_item_id), '') <> 'memory_card'
BEGIN
  SELECT RAISE(ABORT, 'memory_card requires a memory_card study_item');
END;

CREATE TABLE tag (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) > 0),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE study_item_tag (
  study_item_id TEXT NOT NULL REFERENCES study_item(id),
  tag_id TEXT NOT NULL REFERENCES tag(id),
  PRIMARY KEY (study_item_id, tag_id)
) WITHOUT ROWID;

CREATE TABLE item_relation (
  source_item_id TEXT NOT NULL REFERENCES study_item(id),
  target_item_id TEXT NOT NULL REFERENCES study_item(id),
  relation_type TEXT NOT NULL CHECK (relation_type IN (
    'prerequisite', 'similar', 'confusable', 'derived_card', 'related'
  )),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (source_item_id, target_item_id, relation_type),
  CHECK (source_item_id <> target_item_id)
) WITHOUT ROWID;

CREATE TABLE media (
  id TEXT PRIMARY KEY,
  sha256 TEXT NOT NULL UNIQUE CHECK (length(sha256) = 64),
  mime_type TEXT NOT NULL CHECK (length(trim(mime_type)) > 0),
  byte_count INTEGER NOT NULL CHECK (byte_count >= 0),
  width INTEGER CHECK (width IS NULL OR width > 0),
  height INTEGER CHECK (height IS NULL OR height > 0),
  duration_seconds REAL CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  relative_path TEXT NOT NULL UNIQUE CHECK (length(trim(relative_path)) > 0),
  created_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE math_problem_media (
  math_problem_id TEXT NOT NULL REFERENCES math_problem(study_item_id),
  media_id TEXT NOT NULL REFERENCES media(id),
  role TEXT NOT NULL CHECK (role IN ('prompt', 'solution')),
  sort_order INTEGER NOT NULL DEFAULT 0,
  crop_json TEXT,
  PRIMARY KEY (math_problem_id, media_id, role)
) WITHOUT ROWID;

CREATE TABLE attempt (
  id TEXT PRIMARY KEY,
  math_problem_id TEXT NOT NULL REFERENCES math_problem(study_item_id),
  started_at INTEGER NOT NULL,
  finished_at INTEGER,
  result TEXT NOT NULL CHECK (result IN ('again', 'wrong', 'effortful', 'fluent')),
  confidence INTEGER CHECK (confidence IS NULL OR confidence BETWEEN 1 AND 5),
  error_reason TEXT CHECK (error_reason IS NULL OR error_reason IN (
    'concept', 'approach', 'calculation', 'misread', 'forgotten_fact', 'timeout', 'other'
  )),
  reflection_markdown TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX attempt_problem_time_idx ON attempt (math_problem_id, started_at DESC);

CREATE TABLE attempt_media (
  attempt_id TEXT NOT NULL REFERENCES attempt(id),
  media_id TEXT NOT NULL REFERENCES media(id),
  sort_order INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (attempt_id, media_id)
) WITHOUT ROWID;

CREATE TABLE review_log (
  id TEXT PRIMARY KEY,
  study_item_id TEXT NOT NULL REFERENCES study_item(id),
  reviewed_at INTEGER NOT NULL,
  rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 4),
  duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  scheduler_abi_version INTEGER NOT NULL CHECK (scheduler_abi_version > 0),
  state_before INTEGER NOT NULL CHECK (state_before BETWEEN 0 AND 3),
  state_after INTEGER NOT NULL CHECK (state_after BETWEEN 0 AND 3),
  elapsed_days REAL NOT NULL CHECK (elapsed_days >= 0),
  scheduled_days REAL NOT NULL CHECK (scheduled_days > 0),
  retrievability_before REAL NOT NULL CHECK (retrievability_before BETWEEN 0 AND 1),
  difficulty_before REAL NOT NULL CHECK (difficulty_before BETWEEN 0 AND 10),
  difficulty_after REAL NOT NULL CHECK (difficulty_after BETWEEN 1 AND 10),
  stability_before REAL NOT NULL CHECK (stability_before >= 0),
  stability_after REAL NOT NULL CHECK (stability_after > 0),
  due_at_after INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  compensates_log_id TEXT REFERENCES review_log(id),
  created_at INTEGER NOT NULL
);

CREATE INDEX review_log_item_time_idx
  ON review_log (study_item_id, reviewed_at DESC);
CREATE UNIQUE INDEX review_log_compensation_idx
  ON review_log (compensates_log_id)
  WHERE compensates_log_id IS NOT NULL;

CREATE TRIGGER review_log_no_update
BEFORE UPDATE ON review_log
BEGIN
  SELECT RAISE(ABORT, 'review_log is immutable');
END;

CREATE TRIGGER review_log_no_delete
BEFORE DELETE ON review_log
BEGIN
  SELECT RAISE(ABORT, 'review_log is immutable');
END;

PRAGMA user_version = 1;
COMMIT;
