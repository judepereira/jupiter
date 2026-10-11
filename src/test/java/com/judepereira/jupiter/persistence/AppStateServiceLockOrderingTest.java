package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import com.judepereira.jupiter.watch.SessionActivityCoordinator;
import com.judepereira.jupiter.watch.WatchRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AppStateServiceLockOrderingTest {
    @Test
    void waitingForSessionLockDoesNotHoldOnlyDatabaseConnection() throws Exception {
        var db = Files.createTempDirectory("lock-order-").resolve("state.db");
        var sqlite = SQLiteTestSupport.fileBackedDataSource(db);
        var config = new HikariConfig();
        config.setJdbcUrl(sqlite.getUrl());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        try (var dataSource = new HikariDataSource(config); var executor = Executors.newFixedThreadPool(2)) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var encryptor = TestEncryptionSupport.encryptor();
            var repository = new AppStateRepository(jdbc, encryptor, new ObjectMapper());
            var streams = new ActiveStreamRegistryService();
            var coordinator = new SessionActivityCoordinator(streams, new WatchRepository(jdbc, encryptor));
            ApplicationEventPublisher events = event -> {
            };
            var service = new AppStateService(repository, new ObjectMapper(), events, streams, coordinator,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            service.addOrReopenProject("P", db.getParent().toString());
            long session = service.loadViewData().activeSession().id();
            var queued = service.appendUserMessageAndPendingAssistant(session, "user", "assistant", "hello");
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Thread> workerThread = new AtomicReference<>();
            Future<?>[] completionRef = new Future<?>[1];
            coordinator.withLock(session, () -> {
                completionRef[0] = executor.submit(() -> {
                    workerThread.set(Thread.currentThread());
                    attempting.countDown();
                    service.completeAssistantMessage(session, queued.assistantMessage().id(), "done", null);
                });
                try {
                    assertThat(attempting.await(2, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                    while (workerThread.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                        Thread.yield();
                    }
                    assertThat(workerThread.get().getState()).isEqualTo(Thread.State.WAITING);
                    Future<Integer> query = executor.submit(() -> new JdbcTemplate(dataSource)
                            .queryForObject("SELECT COUNT(*) FROM sessions WHERE id=?", Integer.class, session));
                    assertThat(query.get(2, TimeUnit.SECONDS)).isEqualTo(1);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            completionRef[0].get(2, TimeUnit.SECONDS);
        }
    }
}
