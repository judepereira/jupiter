package com.judepereira.jupiter.ui.views;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Log4j2
@RequiredArgsConstructor
public class TaskProjectWatcher {

    private final String projectPath;
    private final Consumer<Path> onChange;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-project-watcher");
        t.setDaemon(true);
        return t;
    });

    private volatile WatchService watchService;

    public void start() {
        Objects.requireNonNull(projectPath, "projectPath is required");
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path root = Paths.get(projectPath);
            registerAll(root);

            exec.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            var kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            Path dir = (Path) key.watchable();
                            Path changed = dir.resolve((Path) event.context());
                            if (changed.toString().contains(FileSystems.getDefault().getSeparator() + ".git")) {
                                continue;
                            }

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                try {
                                    if (Files.isDirectory(changed)) {
                                        registerAll(changed);
                                    }
                                } catch (IOException e) {
                                    log.error("Failed to register newly created directory {}", changed, e);
                                }
                            }

                            try {
                                onChange.accept(changed);
                            } catch (Exception e) {
                                log.error("onChange handler failed", e);
                            }
                        }
                        key.reset();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ClosedWatchServiceException ignored) {
                }
            });

        } catch (IOException e) {
            throw new IllegalStateException("Failed to start watcher: " + e.getMessage(), e);
        }
    }

    private void registerAll(Path start) throws IOException {
        Files.walk(start)
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().equals(".git"))
                .forEach(p -> {
                    try {
                        p.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to register path " + p + ": " + e.getMessage(), e);
                    }
                });
    }

    public void stop() {
        try {
            if (watchService != null) watchService.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stop watcher: " + e.getMessage(), e);
        } finally {
            exec.shutdownNow();
        }
    }
}
