CREATE TABLE service_areas (
    id uuid CONSTRAINT pk_service_areas PRIMARY KEY,
    name varchar(160) NOT NULL,
    polygon geography(Polygon, 4326) NOT NULL,
    CONSTRAINT ck_service_areas_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_service_areas_polygon CHECK (
        NOT ST_IsEmpty(polygon::geometry) AND ST_IsValid(polygon::geometry)
    )
);

CREATE TABLE stores (
    id uuid CONSTRAINT pk_stores PRIMARY KEY,
    name varchar(160) NOT NULL,
    service_area_id uuid NOT NULL,
    location geography(Point, 4326) NOT NULL,
    simulated boolean NOT NULL DEFAULT false,
    CONSTRAINT fk_stores_service_area FOREIGN KEY (service_area_id) REFERENCES service_areas(id),
    CONSTRAINT ck_stores_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_stores_location CHECK (
        NOT ST_IsEmpty(location::geometry) AND ST_IsValid(location::geometry)
    )
);

CREATE TABLE couriers (
    id uuid CONSTRAINT pk_couriers PRIMARY KEY,
    name varchar(160) NOT NULL,
    status varchar(16) NOT NULL,
    location geography(Point, 4326),
    location_updated_at timestamptz,
    simulated boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_couriers_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_couriers_status CHECK (status IN ('OFFLINE', 'AVAILABLE', 'OFFERED', 'BUSY')),
    CONSTRAINT ck_couriers_location CHECK (
        location IS NULL OR (
            NOT ST_IsEmpty(location::geometry) AND ST_IsValid(location::geometry)
        )
    ),
    CONSTRAINT ck_couriers_location_timestamp CHECK (
        (location IS NULL) = (location_updated_at IS NULL)
    ),
    CONSTRAINT ck_couriers_online_location CHECK (status = 'OFFLINE' OR location IS NOT NULL),
    CONSTRAINT ck_couriers_version CHECK (version >= 0)
);

CREATE TABLE orders (
    id uuid CONSTRAINT pk_orders PRIMARY KEY,
    public_code varchar(64) NOT NULL,
    store_id uuid NOT NULL,
    dropoff geography(Point, 4326) NOT NULL,
    status varchar(16) NOT NULL,
    courier_id uuid,
    confirmation_code_hash varchar(255),
    sla_deadline timestamptz NOT NULL,
    next_attempt_at timestamptz,
    search_radius_m integer NOT NULL DEFAULT 2000,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_at timestamptz,
    at_store_at timestamptz,
    picked_up_at timestamptz,
    delivered_at timestamptz,
    canceled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_orders_public_code UNIQUE (public_code),
    CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_orders_courier FOREIGN KEY (courier_id) REFERENCES couriers(id),
    CONSTRAINT ck_orders_public_code CHECK (public_code ~ '^[A-Za-z0-9_-]{20,64}$'),
    CONSTRAINT ck_orders_dropoff CHECK (
        NOT ST_IsEmpty(dropoff::geometry) AND ST_IsValid(dropoff::geometry)
    ),
    CONSTRAINT ck_orders_status CHECK (
        status IN ('SEARCHING', 'ASSIGNED', 'AT_STORE', 'PICKED_UP', 'DELIVERED', 'CANCELED')
    ),
    CONSTRAINT ck_orders_courier CHECK (
        (status <> 'SEARCHING' OR courier_id IS NULL)
        AND (status NOT IN ('ASSIGNED', 'AT_STORE', 'PICKED_UP', 'DELIVERED') OR courier_id IS NOT NULL)
        AND (status <> 'CANCELED' OR ((courier_id IS NULL) = (assigned_at IS NULL)))
    ),
    CONSTRAINT ck_orders_confirmation_hash CHECK (
        confirmation_code_hash IS NULL OR confirmation_code_hash ~ '[^[:space:]]'
    ),
    CONSTRAINT ck_orders_sla CHECK (sla_deadline >= created_at),
    CONSTRAINT ck_orders_search_schedule CHECK (
        (status = 'SEARCHING' AND next_attempt_at IS NOT NULL AND next_attempt_at >= created_at)
        OR (status <> 'SEARCHING' AND next_attempt_at IS NULL)
    ),
    CONSTRAINT ck_orders_search_radius CHECK (search_radius_m > 0),
    CONSTRAINT ck_orders_version CHECK (version >= 0),
    CONSTRAINT ck_orders_timeline CHECK (
        (
            status NOT IN ('SEARCHING', 'ASSIGNED', 'AT_STORE', 'PICKED_UP', 'DELIVERED', 'CANCELED')
            OR (status = 'SEARCHING'
                AND assigned_at IS NULL AND at_store_at IS NULL AND picked_up_at IS NULL
                AND delivered_at IS NULL AND canceled_at IS NULL)
            OR (status = 'ASSIGNED'
                AND assigned_at IS NOT NULL AND at_store_at IS NULL AND picked_up_at IS NULL
                AND delivered_at IS NULL AND canceled_at IS NULL)
            OR (status = 'AT_STORE'
                AND assigned_at IS NOT NULL AND at_store_at IS NOT NULL AND picked_up_at IS NULL
                AND delivered_at IS NULL AND canceled_at IS NULL)
            OR (status = 'PICKED_UP'
                AND assigned_at IS NOT NULL AND at_store_at IS NOT NULL AND picked_up_at IS NOT NULL
                AND delivered_at IS NULL AND canceled_at IS NULL)
            OR (status = 'DELIVERED'
                AND assigned_at IS NOT NULL AND at_store_at IS NOT NULL AND picked_up_at IS NOT NULL
                AND delivered_at IS NOT NULL AND canceled_at IS NULL)
            OR (status = 'CANCELED'
                AND picked_up_at IS NULL AND delivered_at IS NULL AND canceled_at IS NOT NULL
                AND (at_store_at IS NULL OR assigned_at IS NOT NULL))
        )
        AND (assigned_at IS NULL OR assigned_at >= created_at)
        AND (at_store_at IS NULL OR at_store_at >= assigned_at)
        AND (picked_up_at IS NULL OR picked_up_at >= at_store_at)
        AND (delivered_at IS NULL OR delivered_at >= picked_up_at)
        AND (canceled_at IS NULL OR (
            canceled_at >= created_at
            AND (assigned_at IS NULL OR canceled_at >= assigned_at)
            AND (at_store_at IS NULL OR canceled_at >= at_store_at)
        ))
    )
);

CREATE TABLE offers (
    id uuid CONSTRAINT pk_offers PRIMARY KEY,
    order_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    eta_to_pickup_s integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    responded_at timestamptz,
    CONSTRAINT fk_offers_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_offers_courier FOREIGN KEY (courier_id) REFERENCES couriers(id),
    CONSTRAINT ck_offers_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELED')
    ),
    CONSTRAINT ck_offers_eta CHECK (eta_to_pickup_s >= 0),
    CONSTRAINT ck_offers_expiration CHECK (expires_at > created_at),
    CONSTRAINT ck_offers_response CHECK (
        (status <> 'PENDING' OR responded_at IS NULL)
        AND (status NOT IN ('ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELED')
            OR (responded_at IS NOT NULL AND responded_at >= created_at))
    ),
    CONSTRAINT ck_offers_response_deadline CHECK (
        status NOT IN ('ACCEPTED', 'REJECTED', 'EXPIRED')
        OR (status IN ('ACCEPTED', 'REJECTED') AND responded_at < expires_at)
        OR (status = 'EXPIRED' AND responded_at >= expires_at)
    )
);

CREATE TABLE order_events (
    id bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT pk_order_events PRIMARY KEY,
    order_id uuid NOT NULL,
    type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    actor varchar(160) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_version bigint NOT NULL,
    CONSTRAINT fk_order_events_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_order_events_type CHECK (type ~ '[^[:space:]]'),
    CONSTRAINT ck_order_events_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_order_events_actor CHECK (actor ~ '[^[:space:]]'),
    CONSTRAINT ck_order_events_version CHECK (order_version >= 0)
);

CREATE TABLE delivery_tracks (
    order_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    position geography(Point, 4326) NOT NULL,
    CONSTRAINT pk_delivery_tracks PRIMARY KEY (order_id, recorded_at),
    CONSTRAINT fk_delivery_tracks_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_delivery_tracks_position CHECK (
        NOT ST_IsEmpty(position::geometry) AND ST_IsValid(position::geometry)
    )
);

CREATE INDEX idx_service_areas_polygon ON service_areas USING gist (polygon);
CREATE INDEX idx_stores_location ON stores USING gist (location);
CREATE INDEX idx_stores_service_area ON stores (service_area_id);
CREATE INDEX idx_couriers_location ON couriers USING gist (location);
CREATE INDEX idx_couriers_available_location ON couriers USING gist (location)
    WHERE status = 'AVAILABLE';
CREATE INDEX idx_orders_dropoff ON orders USING gist (dropoff);
CREATE INDEX idx_orders_store ON orders (store_id);
CREATE INDEX idx_orders_dispatch_schedule ON orders (status, next_attempt_at);
CREATE UNIQUE INDEX uq_orders_active_courier ON orders (courier_id)
    WHERE status IN ('ASSIGNED', 'AT_STORE', 'PICKED_UP');
CREATE UNIQUE INDEX uq_offers_pending_order ON offers (order_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_offers_pending_courier ON offers (courier_id) WHERE status = 'PENDING';
CREATE INDEX idx_offers_order_history ON offers (order_id, created_at);
CREATE INDEX idx_offers_expiration ON offers (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_order_events_timeline ON order_events (order_id, occurred_at, id);
CREATE INDEX idx_delivery_tracks_position ON delivery_tracks USING gist (position);

CREATE FUNCTION reject_order_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_events permite somente insercao'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER order_events_append_only
    BEFORE UPDATE OR DELETE ON order_events
    FOR EACH ROW EXECUTE FUNCTION reject_order_event_mutation();

CREATE TRIGGER order_events_no_truncate
    BEFORE TRUNCATE ON order_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_order_event_mutation();
