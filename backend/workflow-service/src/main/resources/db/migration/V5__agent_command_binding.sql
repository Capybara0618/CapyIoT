ALTER TABLE work_order_request
  ADD COLUMN agent_confirmation_id VARCHAR(64) NULL,
  ADD COLUMN agent_draft_id VARCHAR(64) NULL,
  ADD COLUMN agent_draft_revision BIGINT NULL,
  ADD COLUMN agent_request_hash VARCHAR(64) NULL;
