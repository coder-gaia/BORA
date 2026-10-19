package dev.bora.realtime.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** LISTEN precisa de uma conexão dedicada: ela nunca ocupa as cinco conexões do pool. */
@Component
public class PostgresChangeBridge implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(PostgresChangeBridge.class);
  private final DataSource source;
  private final AtomicLong revision = new AtomicLong();
  private final AtomicLong notifications = new AtomicLong();
  private volatile boolean running;
  private volatile Connection listening;
  private Thread thread;

  PostgresChangeBridge(DataSource source) {
    this.source = source;
  }

  public long revision() {
    return revision.get();
  }

  public long notifications() {
    return notifications.get();
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    thread = Thread.ofVirtual().name("bora-postgres-listen").start(this::listen);
  }

  private void listen() {
    while (running) {
      try {
        HikariDataSource pool = source.unwrap(HikariDataSource.class);
        Properties properties = new Properties();
        properties.putAll(pool.getDataSourceProperties());
        if (pool.getUsername() != null) {
          properties.setProperty("user", pool.getUsername());
        }
        if (pool.getPassword() != null) {
          properties.setProperty("password", pool.getPassword());
        }
        properties.setProperty("ApplicationName", "bora-realtime-listen");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("tcpKeepAlive", "true");
        try (Connection connection = DriverManager.getConnection(pool.getJdbcUrl(), properties)) {
          listening = connection;
          connection.setAutoCommit(true);
          try (var statement = connection.createStatement()) {
            statement.execute("LISTEN bora_realtime");
          }
          revision.incrementAndGet();
          PGConnection postgres = connection.unwrap(PGConnection.class);
          while (running && !connection.isClosed()) {
            var changes = postgres.getNotifications(1000);
            if (changes != null && changes.length > 0) {
              notifications.addAndGet(changes.length);
              revision.incrementAndGet();
            }
          }
        } finally {
          listening = null;
        }
      } catch (SQLException exception) {
        if (running) {
          LOG.warn("A ponte de tempo real reconectará ao banco: {}", exception.getSQLState());
        }
      }
      if (running) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    Connection connection = listening;
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException ignored) {
        /* encerramento */
      }
    }
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(2500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      thread = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
