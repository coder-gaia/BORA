-- LOCAL TEST FIXTURE ONLY. Holds locks; never changes existing domain rows.
BEGIN;
SET LOCAL application_name = :'fixture_name';
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '15s';
SET LOCAL idle_in_transaction_session_timeout = '60s';
SELECT pg_advisory_xact_lock(hashtextextended('bora-m12-local-operations-fixture',0));
\o /dev/null
SELECT id FROM orders ORDER BY id FOR UPDATE;
\o
WITH free AS MATERIALIZED (
  SELECT c.id,c.status,ST_Y(c.location::geometry) latitude,ST_X(c.location::geometry) longitude
  FROM couriers c
  WHERE c.simulated AND c.status='AVAILABLE' AND c.location IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.courier_id=c.id
      AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP'))
    AND NOT EXISTS (SELECT 1 FROM offers f WHERE f.courier_id=c.id AND f.status='PENDING')
  ORDER BY c.id LIMIT 2
)
SELECT 'BORA_OPS_FIXTURE_READY ' || jsonb_build_object(
  'pid',pg_backend_pid(),'preexistingOrders',(SELECT count(*) FROM orders),
  'couriers',COALESCE((SELECT jsonb_agg(to_jsonb(free)) FROM free),'[]'::jsonb))::text;
-- Keep stdin open. The owner sends heartbeats and always ROLLBACK before EOF.
