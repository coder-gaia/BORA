CREATE TABLE demo_contexts (
    id uuid CONSTRAINT pk_demo_contexts PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_demo_contexts_expiration CHECK (expires_at >= created_at)
);

CREATE INDEX idx_demo_contexts_expiration ON demo_contexts (expires_at);

CREATE TABLE users (
    id uuid CONSTRAINT pk_users PRIMARY KEY,
    context_id uuid NOT NULL,
    display_name varchar(160) NOT NULL,
    role varchar(16) NOT NULL,
    store_id uuid,
    courier_id uuid,
    visitor boolean NOT NULL DEFAULT true,
    expires_at timestamptz NOT NULL,
    CONSTRAINT fk_users_context FOREIGN KEY (context_id) REFERENCES demo_contexts(id),
    CONSTRAINT fk_users_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_users_courier FOREIGN KEY (courier_id) REFERENCES couriers(id),
    CONSTRAINT uq_users_context_role UNIQUE (context_id, role),
    CONSTRAINT uq_users_store UNIQUE (store_id),
    CONSTRAINT uq_users_courier UNIQUE (courier_id),
    CONSTRAINT ck_users_name CHECK (display_name ~ '[^[:space:]]'),
    CONSTRAINT ck_users_role CHECK (role IN ('STORE', 'COURIER', 'OPERATIONS')),
    CONSTRAINT ck_users_role_link CHECK (
        (role = 'STORE' AND store_id IS NOT NULL AND courier_id IS NULL)
        OR (role = 'COURIER' AND store_id IS NULL AND courier_id IS NOT NULL)
        OR (role = 'OPERATIONS' AND store_id IS NULL AND courier_id IS NULL)
    )
);

CREATE INDEX idx_users_expiration ON users (expires_at);

CREATE TABLE spring_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

CREATE TABLE rate_limit_buckets (
    id varchar(160) CONSTRAINT pk_rate_limit_buckets PRIMARY KEY,
    state bytea,
    expires_at bigint
);

CREATE INDEX idx_rate_limit_buckets_expiration ON rate_limit_buckets (expires_at);
