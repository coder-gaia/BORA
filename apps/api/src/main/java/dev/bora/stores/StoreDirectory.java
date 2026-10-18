package dev.bora.stores;

import dev.bora.routing.GeoPoint;
import dev.bora.shared.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StoreDirectory {
  private final JdbcClient jdbc;

  public StoreDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public StoreView find(UUID id) {
    return jdbc.sql(
            """
            SELECT id, name, ST_Y(location::geometry) latitude,
              ST_X(location::geometry) longitude, service_area_id
            FROM stores WHERE id = :id
            """)
        .param("id", id)
        .query(
            (row, index) ->
                new StoreView(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")),
                    row.getObject("service_area_id", UUID.class)))
        .optional()
        .orElseThrow(
            () ->
                new ApiException(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Loja não encontrada."));
  }

  public boolean covers(UUID serviceAreaId, GeoPoint point) {
    return jdbc.sql(
            """
            SELECT ST_Covers(polygon::geometry,
              ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
            FROM service_areas WHERE id = :id
            """)
        .param("id", serviceAreaId)
        .param("longitude", point.longitude())
        .param("latitude", point.latitude())
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }
}
