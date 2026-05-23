-- =====================================================================
-- V2: revoked tokens deny-list for JWT logout
-- =====================================================================

CREATE TABLE revoked_tokens (
    jti         VARCHAR(64) PRIMARY KEY,
    expires_at  TIMESTAMP   NOT NULL,
    revoked_at  TIMESTAMP   NOT NULL
);

-- Used by the scheduled purge job (deletes entries past expires_at)
CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens (expires_at);