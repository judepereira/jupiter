package com.judepereira.jupiter.lifecycle;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** Runtime boundaries used by lifecycle hooks so process execution remains testable. */
public record LifecycleHookRuntime(ExecutorService executor,
                                   LifecycleHookService.ProcessLauncher processLauncher,
                                   Path tempDirectory) {
}
