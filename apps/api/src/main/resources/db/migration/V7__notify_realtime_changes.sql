-- NOTIFY participa da transacao: um rollback nunca publica uma mudanca inexistente.
-- O payload e apenas uma chave curta. Cada instancia consulta o estado persistido.
CREATE INDEX idx_offers_courier_accepted ON offers (courier_id, responded_at DESC)
    WHERE status = 'ACCEPTED';

CREATE FUNCTION notify_realtime_order() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('bora_realtime', 'order:' || NEW.order_id::text);
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_events_realtime
    AFTER INSERT ON order_events
    FOR EACH ROW EXECUTE FUNCTION notify_realtime_order();

CREATE FUNCTION notify_realtime_courier() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('bora_realtime', 'courier:' || NEW.id::text);
    RETURN NEW;
END;
$$;

CREATE TRIGGER couriers_realtime
    AFTER INSERT OR UPDATE OF status, location, location_updated_at ON couriers
    FOR EACH ROW EXECUTE FUNCTION notify_realtime_courier();

CREATE FUNCTION notify_realtime_offer() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('bora_realtime', 'offer:' || NEW.courier_id::text);
    RETURN NEW;
END;
$$;

CREATE TRIGGER offers_realtime
    AFTER INSERT OR UPDATE OF status ON offers
    FOR EACH ROW EXECUTE FUNCTION notify_realtime_offer();
