package com.judepereira.jupiter.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxProcessHardeningProcessTests {

    @Test
    void realLinuxPrctlCallSucceedsInChildJvm() throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")));

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
                java.toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName())
                .redirectErrorStream(true)
                .start();

        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
    }

    public static class Probe {
        public static void main(String[] args) {
            LinuxProcessHardening.enforce();
        }
    }
}
