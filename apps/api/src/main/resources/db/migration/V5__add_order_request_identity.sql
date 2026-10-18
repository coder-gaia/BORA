ALTER TABLE orders
    ADD COLUMN visitor_context_id uuid,
    ADD COLUMN owner_user_id uuid,
    ADD CONSTRAINT fk_orders_visitor_context FOREIGN KEY (visitor_context_id)
        REFERENCES demo_contexts(id),
    ADD CONSTRAINT fk_orders_owner_user FOREIGN KEY (owner_user_id) REFERENCES users(id),
    ADD CONSTRAINT ck_orders_visitor_owner CHECK (
        (visitor_context_id IS NULL) = (owner_user_id IS NULL)
    );

CREATE INDEX idx_orders_visitor_created ON orders (visitor_context_id, created_at DESC, id DESC);

CREATE TABLE order_idempotency (
    context_id uuid NOT NULL REFERENCES demo_contexts(id),
    operation varchar(32) NOT NULL,
    key_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    order_id uuid NOT NULL REFERENCES orders(id),
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_order_idempotency PRIMARY KEY (context_id, operation, key_hash),
    CONSTRAINT ck_order_idempotency_hashes CHECK (
        key_hash ~ '^[0-9a-f]{64}$' AND request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_order_idempotency_response CHECK (
        jsonb_typeof(response_json) = 'object'
        AND NOT (response_json ? 'confirmationCode')
    )
);
