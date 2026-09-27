package com.judepereira.jupiter.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import com.pty4j.PtyProcess;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public class TerminalManagerReplayBufferTests {

    @Test
    public void attachReplaysBufferedOutputToNewSession() throws Exception {
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of(), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-1", "Terminal 1", process);

        runtime.appendOutput("hello from replay");

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        runtime.attach(session);

        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("hello from replay", "\"type\":\"output\"");
    }

    @Test
    void readerDrainRemovesTerminalAfterProcessExit() throws Exception {
        AtomicInteger exitCode = new AtomicInteger();
        TerminalManager manager = new TerminalManager(new ObjectMapper(),
                List.of((terminalId, code) -> exitCode.set(code)), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("output".getBytes()));
        when(process.waitFor()).thenReturn(7);
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-2", "Terminal 2", process);
        manager.registerRuntime("terminal-2", runtime);

        runtime.startReader();
        runtime.closeProcess(Duration.ofSeconds(1));

        assertThat(manager.hasTerminal("terminal-2")).isFalse();
        assertThat(exitCode).hasValue(7);
        verify(process).destroy();
    }

    @Test
    void normalReaderCompletionClosesProcessInputExactlyOnce() throws Exception {
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of(), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        CountingInputStream input = new CountingInputStream("output".getBytes());
        when(process.getInputStream()).thenReturn(input);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.waitFor()).thenReturn(0);
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-close", "Terminal close",
                process);
        manager.registerRuntime("terminal-close", runtime);

        runtime.startReader();
        runtime.closeProcess(Duration.ofSeconds(1));

        assertThat(input.closeCount).hasValue(1);
        assertThat(manager.hasTerminal("terminal-close")).isFalse();
    }

    @Test
    void timeoutCleanupPreventsLateReaderCleanup() throws Exception {
        AtomicInteger exitCode = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of((terminalId, code) -> {
            notifications.incrementAndGet();
            exitCode.set(code);
        }), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch readerFinished = new CountDownLatch(1);
        when(process.getInputStream())
                .thenReturn(new BlockingInputStream(readerStarted, releaseReader, readerFinished));
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-late", "Terminal late",
                process);
        manager.registerRuntime("terminal-late", runtime);

        runtime.startReader();
        assertThat(readerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        runtime.closeProcess(Duration.ZERO);
        assertThat(exitCode).hasValue(-1);
        assertThat(notifications).hasValue(1);

        releaseReader.countDown();
        assertThat(readerFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(notifications).hasValue(1);
        verify(process, times(1)).destroy();
    }

    @Test
    void closeProcessRestoresInterruptAfterFallbackCleanup() throws Exception {
        AtomicInteger exitCode = new AtomicInteger();
        TerminalManager manager = new TerminalManager(new ObjectMapper(),
                List.of((terminalId, code) -> exitCode.set(code)), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch readerFinished = new CountDownLatch(1);
        when(process.getInputStream())
                .thenReturn(new BlockingInputStream(readerStarted, releaseReader, readerFinished));
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-interrupt",
                "Terminal interrupt", process);
        manager.registerRuntime("terminal-interrupt", runtime);
        runtime.startReader();
        assertThat(readerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean();
        Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            runtime.closeProcess(Duration.ofSeconds(1));
            restored.set(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            finished.countDown();
        });
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        closer.join();
        assertThat(restored).isTrue();
        assertThat(exitCode).hasValue(-1);
        releaseReader.countDown();
        assertThat(readerFinished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cleanupStillHappensWhenDestroyFailsAfterTimeout() throws Exception {
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of(), new RuntimeEnvironment(Map.of()));
        PtyProcess process = mock(PtyProcess.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        when(process.getInputStream()).thenReturn(new BlockingInputStream(readerStarted, releaseReader));
        doThrow(new IllegalStateException("destroy failed")).when(process).destroy();
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-3", "Terminal 3", process);
        manager.registerRuntime("terminal-3", runtime);
        runtime.startReader();
        assertThat(readerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Assertions.assertThatThrownBy(() -> runtime.closeProcess(Duration.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessage("destroy failed");
        assertThat(manager.hasTerminal("terminal-3")).isFalse();
        assertThat(releaseReader.await(1, TimeUnit.SECONDS)).isTrue();
        verify(process).getInputStream();
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private final AtomicInteger closeCount = new AtomicInteger();

        private CountingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final CountDownLatch finished;

        private BlockingInputStream(CountDownLatch started, CountDownLatch release) {
            this(started, release, new CountDownLatch(0));
        }

        private BlockingInputStream(CountDownLatch started, CountDownLatch release, CountDownLatch finished) {
            this.started = started;
            this.release = release;
            this.finished = finished;
        }

        @Override
        public int read() throws IOException {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
            return -1;
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
