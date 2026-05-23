-- =====================================================================
-- IssueFlow initial schema
-- One migration covering every table, FK, index, and check constraint
-- required by the v0.2 entity model. Future schema changes are added as
-- V2__*.sql, V3__*.sql, etc. — never edit this file after deployment.
-- =====================================================================

-- ─── users ───────────────────────────────────────────────────────────
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN','DEVELOPER'))
);

-- Case-insensitive uniqueness on username (login + @mention resolution).
CREATE UNIQUE INDEX uk_users_username_lower ON users (LOWER(username));

-- ─── projects ────────────────────────────────────────────────────────
CREATE TABLE projects (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(2000),
    owner_id        BIGINT        NOT NULL,
    deleted_at      TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT fk_projects_owner
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_projects_deleted_at ON projects (deleted_at);

-- ─── tickets ─────────────────────────────────────────────────────────
CREATE TABLE tickets (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200)  NOT NULL,
    description     VARCHAR(5000),
    status          VARCHAR(32)   NOT NULL,
    priority        VARCHAR(32)   NOT NULL,
    type            VARCHAR(32)   NOT NULL,
    project_id      BIGINT        NOT NULL,
    assignee_id     BIGINT,
    due_date        TIMESTAMP,
    is_overdue      BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT fk_tickets_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tickets_assignee
        FOREIGN KEY (assignee_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT ck_tickets_status   CHECK (status   IN ('TODO','IN_PROGRESS','IN_REVIEW','DONE')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_tickets_type     CHECK (type     IN ('BUG','FEATURE','TECHNICAL'))
);

CREATE INDEX idx_tickets_project_deleted ON tickets (project_id, deleted_at);
CREATE INDEX idx_tickets_assignee_status ON tickets (assignee_id, status);

-- Partial index used by the overdue-escalation scheduler. Indexes only
-- the rows the scheduler actually scans: have a due date, not yet at
-- the terminal escalated state, and not soft-deleted.
CREATE INDEX idx_tickets_overdue_scan ON tickets (due_date)
    WHERE is_overdue = false AND deleted_at IS NULL;

-- ─── comments ────────────────────────────────────────────────────────
CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT        NOT NULL,
    author_id       BIGINT        NOT NULL,
    content         VARCHAR(5000) NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT fk_comments_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_author
        FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_comments_ticket ON comments (ticket_id);

-- ─── comment_mentions ───────────────────────────────────────────────
CREATE TABLE comment_mentions (
    id                  BIGSERIAL PRIMARY KEY,
    comment_id          BIGINT    NOT NULL,
    mentioned_user_id   BIGINT    NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_comment_mentions_comment
        FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_mentions_user
        FOREIGN KEY (mentioned_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_comment_mention UNIQUE (comment_id, mentioned_user_id)
);

CREATE INDEX idx_comment_mentions_user ON comment_mentions (mentioned_user_id);

-- ─── ticket_dependencies ────────────────────────────────────────────
CREATE TABLE ticket_dependencies (
    ticket_id           BIGINT    NOT NULL,
    blocker_ticket_id   BIGINT    NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    PRIMARY KEY (ticket_id, blocker_ticket_id),
    CONSTRAINT fk_ticket_dep_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_dep_blocker
        FOREIGN KEY (blocker_ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT ck_ticket_dep_no_self_block
        CHECK (ticket_id <> blocker_ticket_id)
);

CREATE INDEX idx_ticket_dep_blocker ON ticket_dependencies (blocker_ticket_id);

-- ─── attachments ────────────────────────────────────────────────────
CREATE TABLE attachments (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT       NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    data            BYTEA        NOT NULL,
    uploaded_by_id  BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_attachments_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachments_uploader
        FOREIGN KEY (uploaded_by_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_attachments_size_positive   CHECK (size_bytes > 0),
    CONSTRAINT ck_attachments_size_max_10mb   CHECK (size_bytes <= 10485760),
    CONSTRAINT ck_attachments_content_type    CHECK (
        content_type IN ('image/png','image/jpeg','application/pdf','text/plain')
    )
);

CREATE INDEX idx_attachments_ticket ON attachments (ticket_id);

-- ─── audit_logs ─────────────────────────────────────────────────────
CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    action          VARCHAR(32) NOT NULL,
    entity_type     VARCHAR(32) NOT NULL,
    entity_id       BIGINT      NOT NULL,
    actor           VARCHAR(16) NOT NULL,
    performed_by    BIGINT,
    metadata_json   TEXT,
    created_at      TIMESTAMP   NOT NULL,
    CONSTRAINT ck_audit_actor CHECK (actor IN ('USER','SYSTEM')),
    CONSTRAINT ck_audit_action CHECK (action IN (
        'USER_CREATE','USER_UPDATE','USER_DELETE',
        'PROJECT_CREATE','PROJECT_UPDATE','PROJECT_DELETE','PROJECT_RESTORE',
        'TICKET_CREATE','TICKET_UPDATE','TICKET_DELETE','TICKET_RESTORE',
        'TICKET_STATUS_CHANGE','TICKET_PRIORITY_CHANGE','TICKET_ASSIGN',
        'AUTO_ASSIGN','AUTO_ESCALATE',
        'COMMENT_CREATE','COMMENT_UPDATE','COMMENT_DELETE',
        'DEPENDENCY_ADD','DEPENDENCY_REMOVE',
        'ATTACHMENT_UPLOAD','ATTACHMENT_DELETE',
        'TICKET_IMPORT',
        'LOGIN','LOGOUT'
    )),
    CONSTRAINT ck_audit_entity_type CHECK (entity_type IN (
        'USER','PROJECT','TICKET','COMMENT','DEPENDENCY','ATTACHMENT'
    ))
);

CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_created_at ON audit_logs (created_at DESC);