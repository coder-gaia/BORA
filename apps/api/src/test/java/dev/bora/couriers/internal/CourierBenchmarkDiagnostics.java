package dev.bora.couriers.internal;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Instrumentação optativa e restrita ao benchmark, sem registrar parâmetros SQL. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bora.benchmark.diagnostics", havingValue = "true")
class CourierBenchmarkDiagnostics {
  @Bean
  static JdbcMetrics jdbcBenchmarkMetrics() {
    return new JdbcMetrics();
  }

  static final class JdbcMetrics implements BeanPostProcessor, DisposableBean {
    private final Map<String, Timing> timings = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sampler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "benchmark-pool-sampler");
              thread.setDaemon(true);
              return thread;
            });
    private volatile HikariDataSource pool;
    private final AtomicLong maxActive = new AtomicLong();
    private final AtomicLong maxWaiting = new AtomicLong();
    private final LongAdder poolSamples = new LongAdder();
    private final LongAdder waitingSamples = new LongAdder();

    JdbcMetrics() {
      sampler.scheduleAtFixedRate(this::samplePool, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      if (!(bean instanceof DataSource source)) return bean;
      if (source instanceof HikariDataSource hikari) pool = hikari;
      return Proxy.newProxyInstance(
          DataSource.class.getClassLoader(),
          new Class<?>[] {DataSource.class},
          (proxy, method, arguments) -> {
            if (!method.getName().equals("getConnection")) return invoke(source, method, arguments);
            long started = System.nanoTime();
            Connection connection;
            try {
              connection = (Connection) invoke(source, method, arguments);
            } finally {
              record("[pool acquire]", started);
            }
            return connection(connection);
          });
    }

    private Connection connection(Connection delegate) {
      long borrowed = System.nanoTime();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("prepareStatement")) {
                  PreparedStatement statement =
                      (PreparedStatement) invoke(delegate, method, arguments);
                  String sql = ((String) arguments[0]).replaceAll("\\s+", " ").trim();
                  return statement(statement, sql);
                }
                if (name.equals("commit") || name.equals("rollback")) {
                  long started = System.nanoTime();
                  try {
                    return invoke(delegate, method, arguments);
                  } finally {
                    record("[transaction " + name + "]", started);
                  }
                }
                if (name.equals("close")) {
                  try {
                    return invoke(delegate, method, arguments);
                  } finally {
                    record("[connection lease]", borrowed);
                  }
                }
                return invoke(delegate, method, arguments);
              });
    }

    private PreparedStatement statement(PreparedStatement delegate, String sql) {
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, arguments) -> {
                if (!method.getName().startsWith("execute"))
                  return invoke(delegate, method, arguments);
                long started = System.nanoTime();
                try {
                  return invoke(delegate, method, arguments);
                } finally {
                  record(sql, started);
                }
              });
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }

    private void record(String operation, long started) {
      Timing timing = timings.computeIfAbsent(operation, ignored -> new Timing());
      long elapsed = System.nanoTime() - started;
      timing.count.increment();
      timing.nanos.add(elapsed);
      timing.maxNanos.accumulateAndGet(elapsed, Math::max);
    }

    private void samplePool() {
      HikariDataSource current = pool;
      if (current == null || current.getHikariPoolMXBean() == null) return;
      var metrics = current.getHikariPoolMXBean();
      int waiting = metrics.getThreadsAwaitingConnection();
      poolSamples.increment();
      if (waiting != 0) waitingSamples.increment();
      maxActive.accumulateAndGet(metrics.getActiveConnections(), Math::max);
      maxWaiting.accumulateAndGet(waiting, Math::max);
    }

    void reset() {
      timings.clear();
      maxActive.set(0);
      maxWaiting.set(0);
      poolSamples.reset();
      waitingSamples.reset();
    }

    Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("maxActiveConnections", maxActive.get());
      result.put("maxThreadsWaitingForConnection", maxWaiting.get());
      result.put("poolSamples", poolSamples.sum());
      result.put("poolSamplesWithWaiters", waitingSamples.sum());
      List<Map<String, Object>> operations =
          timings.entrySet().stream()
              .sorted(
                  Comparator.comparingLong(
                          (Map.Entry<String, Timing> entry) -> entry.getValue().nanos.sum())
                      .reversed())
              .limit(30)
              .map(
                  entry -> {
                    Timing timing = entry.getValue();
                    Map<String, Object> operation = new LinkedHashMap<>();
                    operation.put("operation", entry.getKey());
                    operation.put("count", timing.count.sum());
                    operation.put("totalMillis", timing.nanos.sum() / 1_000_000.0);
                    operation.put(
                        "meanMillis", timing.nanos.sum() / 1_000_000.0 / timing.count.sum());
                    operation.put("maxMillis", timing.maxNanos.get() / 1_000_000.0);
                    return operation;
                  })
              .toList();
      result.put("operations", operations);
      return result;
    }

    @Override
    public void destroy() {
      sampler.shutdownNow();
    }

    private static final class Timing {
      private final LongAdder count = new LongAdder();
      private final LongAdder nanos = new LongAdder();
      private final AtomicLong maxNanos = new AtomicLong();
    }
  }
}
