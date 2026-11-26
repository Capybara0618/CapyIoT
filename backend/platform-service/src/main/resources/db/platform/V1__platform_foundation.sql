CREATE TABLE app_user (
 user_id VARCHAR(64) PRIMARY KEY, login_name VARCHAR(64) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL, contact VARCHAR(255), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 authorization_version BIGINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK (status IN ('ACTIVE','DISABLED','LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_credential (
 user_id VARCHAR(64) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, credential_version BIGINT NOT NULL DEFAULT 1,
 password_changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), failed_attempt_count INT NOT NULL DEFAULT 0,
 locked_until TIMESTAMP(3), FOREIGN KEY (user_id) REFERENCES app_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_access_grant (
 grant_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, role_code VARCHAR(32) NOT NULL,
 scope_type VARCHAR(32) NOT NULL, park_id VARCHAR(64), assignment_relation VARCHAR(16),
 scope_key_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 granted_by VARCHAR(64) NOT NULL, granted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 revoked_by VARCHAR(64), revoked_at TIMESTAMP(3), revoked_reason VARCHAR(1000), version BIGINT NOT NULL DEFAULT 0,
 active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_active_grant (user_id,role_code,scope_key_hash,active_marker),
 FOREIGN KEY (user_id) REFERENCES app_user(user_id),
 CHECK (scope_type IN ('NONE','PARK','AREA_SUBTREE','ASSIGNED_TO_ME')),
 CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
 CHECK (role_code IN ('SYS_ADMIN','OPS_SUPERVISOR','OPS_OPERATOR','REPAIR_TECH','ACCEPTANCE_STAFF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_access_grant_area (
 grant_id VARCHAR(64) NOT NULL, root_area_id VARCHAR(64) NOT NULL, PRIMARY KEY(grant_id,root_area_id),
 FOREIGN KEY (grant_id) REFERENCES user_access_grant(grant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE login_session (
 session_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), last_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 expires_at TIMESTAMP(3) NOT NULL, revoked_at TIMESTAMP(3), revoked_reason VARCHAR(1000), client_info_json JSON, jti VARCHAR(64) NOT NULL UNIQUE,
 FOREIGN KEY(user_id) REFERENCES app_user(user_id), INDEX ix_session_user(user_id,status),
 CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE audit_record (
 audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_type VARCHAR(16) NOT NULL, actor_id VARCHAR(64) NOT NULL,
 actor_user_id VARCHAR(64), actor_role_code VARCHAR(32), actor_name_snapshot VARCHAR(100),
 action_code VARCHAR(64) NOT NULL, resource_type VARCHAR(64) NOT NULL, resource_id VARCHAR(64),
 park_id VARCHAR(64), area_id VARCHAR(64), result VARCHAR(16) NOT NULL, source VARCHAR(32) NOT NULL,
 reason VARCHAR(1000), change_summary VARCHAR(1000), before_version BIGINT, after_version BIGINT,
 request_id VARCHAR(128), correlation_id VARCHAR(128), causation_id VARCHAR(128), session_id VARCHAR(64),
 run_id VARCHAR(64), tool_call_id VARCHAR(64), draft_id VARCHAR(64), permission_code VARCHAR(64),
 authorization_version BIGINT, details_json JSON, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_audit_time(created_at,audit_id), INDEX ix_audit_actor(actor_user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
