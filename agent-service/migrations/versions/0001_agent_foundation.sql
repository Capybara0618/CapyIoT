CREATE TABLE conversation (
	conversation_id VARCHAR(64) NOT NULL,
	owner_user_id VARCHAR(64) NOT NULL,
	title VARCHAR(200) NOT NULL,
	status VARCHAR(16) NOT NULL,
	created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	last_message_at DATETIME(3),
	archived_at DATETIME(3),
	version BIGINT NOT NULL,
	PRIMARY KEY (conversation_id),
	CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE','ARCHIVED'))
);

CREATE TABLE conversation_message (
	message_id VARCHAR(64) NOT NULL,
	conversation_id VARCHAR(64) NOT NULL,
	message_seq BIGINT NOT NULL,
	`role` VARCHAR(16) NOT NULL,
	content TEXT NOT NULL,
	content_type VARCHAR(32) NOT NULL,
	content_status VARCHAR(40) NOT NULL,
	run_id VARCHAR(64),
	created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	PRIMARY KEY (message_id),
	UNIQUE (conversation_id, message_seq),
	CHECK (role IN ('USER','ASSISTANT')),
	FOREIGN KEY(conversation_id) REFERENCES conversation (conversation_id)
);

CREATE TABLE agent_run (
	run_id VARCHAR(64) NOT NULL,
	conversation_id VARCHAR(64) NOT NULL,
	request_message_id VARCHAR(64) NOT NULL,
	owner_user_id VARCHAR(64) NOT NULL,
	intent_type VARCHAR(32) NOT NULL,
	status VARCHAR(32) NOT NULL,
	idempotency_key VARCHAR(128) NOT NULL,
	request_hash VARCHAR(64) NOT NULL,
	started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	finished_at DATETIME(3),
	current_step_no BIGINT NOT NULL,
	tool_call_count BIGINT NOT NULL,
	correlation_id VARCHAR(64) NOT NULL,
	failure_code VARCHAR(64),
	answer_message_id VARCHAR(64),
	version BIGINT NOT NULL,
	PRIMARY KEY (run_id),
	UNIQUE (owner_user_id, idempotency_key),
	CHECK (status IN ('CREATED','PLANNING','RUNNING','WAITING_CONFIRMATION','SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
	FOREIGN KEY(conversation_id) REFERENCES conversation (conversation_id),
	UNIQUE (request_message_id),
	FOREIGN KEY(request_message_id) REFERENCES conversation_message (message_id)
);

CREATE TABLE agent_step (
	step_id VARCHAR(64) NOT NULL,
	run_id VARCHAR(64) NOT NULL,
	sequence_no BIGINT NOT NULL,
	step_type VARCHAR(32) NOT NULL,
	status VARCHAR(32) NOT NULL,
	summary VARCHAR(1000) NOT NULL,
	started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	finished_at DATETIME(3),
	error_code VARCHAR(64),
	latency_ms BIGINT,
	PRIMARY KEY (step_id),
	UNIQUE (run_id, sequence_no),
	FOREIGN KEY(run_id) REFERENCES agent_run (run_id)
);

CREATE TABLE operation_draft (
	draft_id VARCHAR(64) NOT NULL,
	run_id VARCHAR(64) NOT NULL,
	conversation_id VARCHAR(64) NOT NULL,
	owner_user_id VARCHAR(64) NOT NULL,
	action_type VARCHAR(32) NOT NULL,
	source_type VARCHAR(32) NOT NULL,
	payload_json JSON NOT NULL,
	related_evidence_ids_json JSON NOT NULL,
	target_park_id VARCHAR(64) NOT NULL,
	revision INTEGER NOT NULL,
	content_hash VARCHAR(64) NOT NULL,
	status VARCHAR(32) NOT NULL,
	presented_at DATETIME(3),
	expires_at DATETIME(3) NOT NULL,
	confirmed_by VARCHAR(64),
	confirmed_at DATETIME(3),
	result_business_object_type VARCHAR(32),
	result_business_object_id VARCHAR(64),
	version BIGINT NOT NULL,
	PRIMARY KEY (draft_id),
	CHECK (action_type='WORK_ORDER' AND source_type='USER_REQUEST'),
	CHECK (status IN ('DRAFTED','PENDING_CONFIRMATION','CONFIRMED','EXECUTING','EXECUTED','REJECTED','INVALIDATED','EXPIRED','FAILED')),
	FOREIGN KEY(run_id) REFERENCES agent_run (run_id),
	FOREIGN KEY(conversation_id) REFERENCES conversation (conversation_id)
);

CREATE TABLE tool_call_record (
	tool_call_id VARCHAR(64) NOT NULL,
	run_id VARCHAR(64) NOT NULL,
	sequence_no BIGINT NOT NULL,
	tool_name VARCHAR(64) NOT NULL,
	schema_version INTEGER NOT NULL,
	input_summary VARCHAR(1000) NOT NULL,
	input_hash VARCHAR(64) NOT NULL,
	authorization_decision VARCHAR(32) NOT NULL,
	status VARCHAR(32) NOT NULL,
	result_summary VARCHAR(1000),
	result_ref VARCHAR(128),
	error_code VARCHAR(64),
	retry_count INTEGER NOT NULL,
	latency_ms BIGINT,
	started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	finished_at DATETIME(3),
	PRIMARY KEY (tool_call_id),
	UNIQUE (run_id, sequence_no),
	CHECK (status IN ('STARTED','SUCCEEDED','EMPTY','REJECTED','FAILED','TIMED_OUT')),
	FOREIGN KEY(run_id) REFERENCES agent_run (run_id)
);

CREATE TABLE confirmation_record (
	confirmation_id VARCHAR(64) NOT NULL,
	draft_id VARCHAR(64) NOT NULL,
	draft_revision INTEGER NOT NULL,
	content_hash VARCHAR(64) NOT NULL,
	actor_user_id VARCHAR(64) NOT NULL,
	action VARCHAR(16) NOT NULL,
	confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	request_id VARCHAR(128) NOT NULL,
	authorization_result VARCHAR(32) NOT NULL,
	idempotency_key VARCHAR(128) NOT NULL,
	PRIMARY KEY (confirmation_id),
	UNIQUE (draft_id, draft_revision, action),
	CHECK (action IN ('CONFIRM','REJECT')),
	FOREIGN KEY(draft_id) REFERENCES operation_draft (draft_id)
);

CREATE TABLE evidence_reference (
	evidence_id VARCHAR(64) NOT NULL,
	run_id VARCHAR(64) NOT NULL,
	tool_call_id VARCHAR(64) NOT NULL,
	claim_id VARCHAR(64),
	source_context VARCHAR(32) NOT NULL,
	source_type VARCHAR(32) NOT NULL,
	source_id VARCHAR(64) NOT NULL,
	source_version BIGINT,
	source_event_sequence BIGINT,
	occurred_at DATETIME(3),
	retrieved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	source_mode VARCHAR(32) NOT NULL,
	relation VARCHAR(32) NOT NULL,
	evidence_summary VARCHAR(2000) NOT NULL,
	freshness_status VARCHAR(32) NOT NULL,
	source_link VARCHAR(256),
	PRIMARY KEY (evidence_id),
	FOREIGN KEY(run_id) REFERENCES agent_run (run_id),
	FOREIGN KEY(tool_call_id) REFERENCES tool_call_record (tool_call_id)
);

CREATE TABLE operation_draft_revision (
	draft_id VARCHAR(64) NOT NULL,
	revision INTEGER NOT NULL,
	content_json JSON NOT NULL,
	content_hash VARCHAR(64) NOT NULL,
	created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	created_by_run_id VARCHAR(64) NOT NULL,
	PRIMARY KEY (draft_id, revision),
	FOREIGN KEY(draft_id) REFERENCES operation_draft (draft_id),
	FOREIGN KEY(created_by_run_id) REFERENCES agent_run (run_id)
);

CREATE TABLE agent_command_outbox (
	command_id VARCHAR(64) NOT NULL,
	draft_id VARCHAR(64) NOT NULL,
	draft_revision INTEGER NOT NULL,
	confirmation_id VARCHAR(64) NOT NULL,
	actor_user_id VARCHAR(64) NOT NULL,
	command_type VARCHAR(32) NOT NULL,
	idempotency_key VARCHAR(128) NOT NULL,
	request_hash VARCHAR(64) NOT NULL,
	payload_json JSON NOT NULL,
	status VARCHAR(32) NOT NULL,
	attempt_count INTEGER NOT NULL,
	next_retry_at DATETIME(3),
	lease_until DATETIME(3),
	created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
	last_error VARCHAR(1000),
	PRIMARY KEY (command_id),
	CHECK (command_type='CREATE_WORK_ORDER'),
	CHECK (status IN ('PENDING','DISPATCHING','RETRY','SUCCEEDED','DEAD','RECONCILIATION_REQUIRED')),
	FOREIGN KEY(draft_id) REFERENCES operation_draft (draft_id),
	UNIQUE (confirmation_id),
	FOREIGN KEY(confirmation_id) REFERENCES confirmation_record (confirmation_id),
	UNIQUE (idempotency_key)
);

CREATE TABLE execution_record (
	execution_id VARCHAR(64) NOT NULL,
	draft_id VARCHAR(64) NOT NULL,
	confirmation_id VARCHAR(64) NOT NULL,
	target_command VARCHAR(64) NOT NULL,
	status VARCHAR(32) NOT NULL,
	idempotency_key VARCHAR(128) NOT NULL,
	request_hash VARCHAR(64) NOT NULL,
	target_object_type VARCHAR(32),
	target_object_id VARCHAR(64),
	started_at DATETIME(3),
	finished_at DATETIME(3),
	error_code VARCHAR(64),
	error_summary VARCHAR(1000),
	attempt_count INTEGER NOT NULL,
	version BIGINT NOT NULL,
	PRIMARY KEY (execution_id),
	CHECK (status IN ('QUEUED','EXECUTING','SUCCEEDED','FAILED','RECONCILIATION_REQUIRED')),
	FOREIGN KEY(draft_id) REFERENCES operation_draft (draft_id),
	FOREIGN KEY(confirmation_id) REFERENCES confirmation_record (confirmation_id),
	UNIQUE (idempotency_key)
);

CREATE INDEX ix_conversation_owner_user_id ON conversation (owner_user_id);

CREATE INDEX ix_conversation_message_run_id ON conversation_message (run_id);

CREATE INDEX ix_operation_draft_owner_user_id ON operation_draft (owner_user_id);

CREATE INDEX ix_agent_dispatch ON agent_command_outbox (status, next_retry_at);
