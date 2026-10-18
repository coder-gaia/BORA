package dev.bora.dispatch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class OfferAudit {
  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  OfferAudit(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  void write(
      UUID order,
      String type,
      UUID offer,
      UUID courier,
      OfferStatus from,
      OfferStatus to,
      String actor,
      Instant now) {
    var payload = new LinkedHashMap<String, Object>();
    if (offer != null) payload.put("offerId", offer.toString());
    if (courier != null) payload.put("courierId", courier.toString());
    if (from != null) payload.put("fromOfferStatus", from.name());
    if (to != null) payload.put("toOfferStatus", to.name());
    jdbc.sql(
            """
        INSERT INTO order_events(order_id,type,payload,actor,occurred_at,order_version)
        SELECT id,:type,CAST(:payload AS jsonb) || jsonb_build_object('from',status,'to',status),
          :actor,:now,version FROM orders WHERE id=:order
        """)
        .param("order", order)
        .param("type", type)
        .param("payload", mapper.writeValueAsString(payload))
        .param("actor", actor)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .update();
  }
}
