ALTER TABLE couriers
    ADD COLUMN position_epoch bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_couriers_position_epoch CHECK (position_epoch >= 0);
