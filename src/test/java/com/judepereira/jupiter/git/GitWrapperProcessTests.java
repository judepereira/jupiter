package com.judepereira.jupiter.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWrapperProcessTests {
    private static final String TRAILER = "Co-authored-by: Jupiter IDE <340693705+jupiter-ide@users.noreply.github.com>";
    private static final String REAL_GIT = "/usr/bin/git";
    private static final Path WRAPPER = Path.of("git-wrapper.sh").toAbsolutePath();
    private static final List<String> CONFIG_VARS = List.of("GIT_CONFIG", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_AUTHOR_NAME",
            "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL", "GIT_COMMITTER_DATE",
            "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0", "GIT_CONFIG_KEY_1", "GIT_CONFIG_VALUE_1", "GIT_CONFIG_KEY_2",
            "GIT_CONFIG_VALUE_2", "EMAIL");

    @TempDir
    Path temp;

    @Test
    void ordinaryMessageGetsTrailerAndPreservesIdentity() throws Exception {
        Repo r = repo();
        r.commit("-m", "ordinary subject");
        assertTrailerCount(r, 1);
        assertIdentity(r);
    }

    @Test
    void existingAndNonAdjacentCoauthorsAreNotDuplicated() throws Exception {
        Repo r = repo();
        String other = "Co-authored-by: Other Person <other@example.com>";
        String third = "Reviewed-by: Reviewer <reviewer@example.com>";
        r.commit("-m", "subject\n\nBody\n\n" + other + "\n" + third + "\n" + TRAILER);
        String body = r.git("show", "-s", "--format=%B");
        assertEquals(1, body.lines().filter(TRAILER::equals).count());
        assertEquals(1, body.lines().filter(other::equals).count());
    }

    @Test
    void fileMessageAmendNoEditAndPathspecSeparatorWork() throws Exception {
        Repo r = repo();
        Path message = temp.resolve("message");
        Files.writeString(message, "from file\n");
        r.git("commit", "-F", message.toString());
        assertTrailerCount(r, 1);
        Files.writeString(r.repo.resolve("--odd"), "odd");
        r.git("add", "--", "--odd");
        r.git("commit", "--amend", "--no-edit");
        assertTrailerCount(r, 1);
    }

    @Test
    void commitOptionsAndRepositoryOverridesAreForwarded() throws Exception {
        Repo r = repo();
        r.git("-C", r.repo.toString(), "-c", "user.name=Inline", "-c", "user.email=inline@example.com", "commit", "-m",
                "separate config");
        assertEquals("Inline <inline@example.com>", r.git("show", "-s", "--format=%an <%ae>").trim());
        Result invalidConfig = r.invoke("-cuser.name=Inline", "--version");
        assertEquals(129, invalidConfig.exitCode);
        Path gitDir = r.repo.resolve(".git");
        r.git("--git-dir=" + gitDir, "--work-tree", r.repo.toString(), "commit", "--amend", "-m", "overrides");
        assertTrailerCount(r, 1);
    }

    @Test
    void helpLikeMessageAndPathspecAreNotMistakenForHelp() throws Exception {
        Repo r = repo();
        r.commit("-m", "--help");
        assertTrue(r.git("show", "-s", "--format=%s").trim().equals("--help"));
        assertTrailerCount(r, 1);

        Files.writeString(r.repo.resolve("--help"), "help filename");
        r.git("add", "--", "--help");
        r.commit("-m", "pathspec help");
        assertTrailerCount(r, 1);
        assertTrue(r.git("show", "--format=", "--", "--help").contains("help filename"));
    }

    @Test
    void fixupSquashAndGitOptionPassthroughMatchNativeGit() throws Exception {
        Repo r = repo();
        r.commit("-m", "base");
        r.commit("--fixup=amend:HEAD", "--no-edit");
        assertTrailerCount(r, 1);
        r.commit("--fixup=reword:HEAD", "--no-edit");
        assertTrailerCount(r, 1);
        Files.writeString(r.repo.resolve("file"), "squash change");
        r.git("add", "file");
        r.commit("--squash=HEAD", "-m", "squash message");
        assertTrailerCount(r, 1);

        for (String option : List.of("--version", "--exec-path")) {
            assertEquals(r.invokeDirect(option).exitCode, r.invoke(option).exitCode);
        }
        Result invalid = r.invoke("-C/definitely-not-a-git-repository", "status");
        Result direct = r.invokeDirect("-C/definitely-not-a-git-repository", "status");
        assertEquals(direct.exitCode, invalid.exitCode);
    }

    @Test
    void customHooksPathRunsAndNoVerifySkipsFailingHook() throws Exception {
        Repo r = repo();
        Path hooks = temp.resolve("custom-hooks");
        Files.createDirectories(hooks);
        Path marker = temp.resolve("hook-ran");
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\necho ran >> '" + marker + "'\nexit 1\n");
        hook.toFile().setExecutable(true);
        r.git("config", "core.hooksPath", hooks.toString());
        Result wrapped = r.invoke("commit", "-m", "blocked");
        Result direct = r.invokeDirect("commit", "-m", "blocked");
        assertEquals(direct.exitCode, wrapped.exitCode);
        assertEquals(1, wrapped.exitCode);
        assertTrue(Files.exists(marker));
        assertFalse(r.invoke("rev-parse", "--verify", "HEAD").exitCode == 0);
        Files.delete(marker);
        r.git("commit", "--no-verify", "-m", "allowed");
        assertFalse(Files.exists(marker));
    }

    @Test
    void configuredTrailerPoliciesAndKeyOverrideAreRespected() throws Exception {
        Repo r = repo();
        r.git("config", "trailer.Co-authored-by.ifexists", "add");
        r.git("config", "trailer.Co-authored-by.ifmissing", "doNothing");
        r.git("config", "trailer.Co-authored-by.key", "Pair");
        r.commit("-m", "subject\n\nCo-authored-by: Other <other@example.com>");
        String body = r.git("show", "-s", "--format=%B");
        assertFalse(body.contains("Pair:"));
        assertTrue(body.contains("Co-authored-by: Other <other@example.com>"));
        assertTrue(body.contains(TRAILER));
    }

    private Repo repo() throws Exception {
        Path home = Files.createDirectory(temp.resolve("home-" + System.nanoTime()));
        Path xdg = Files.createDirectory(home.resolve("xdg"));
        Path repo = Files.createDirectory(temp.resolve("repo-" + System.nanoTime()));
        Repo r = new Repo(home, xdg, repo);
        r.git("init", "-q");
        r.git("config", "user.name", "Test User");
        r.git("config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("file"), "content");
        r.git("add", "--", "file");
        return r;
    }

    private void assertTrailerCount(Repo r, long count) throws Exception {
        assertEquals(count, r.git("show", "-s", "--format=%B").lines().filter(TRAILER::equals).count());
    }

    private void assertIdentity(Repo r) throws Exception {
        assertEquals("Test User <test@example.com>", r.git("show", "-s", "--format=%an <%ae>").trim());
        assertEquals("Test User <test@example.com>", r.git("show", "-s", "--format=%cn <%ce>").trim());
    }

    private static String[] concat(String[] first, String[] rest) {
        String[] all = Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

    private record Result(int exitCode, String output) {
    }

    private final class Repo {
        final Path home, xdg, repo;
        Repo(Path home, Path xdg, Path repo) {
            this.home = home;
            this.xdg = xdg;
            this.repo = repo;
        }
        void commit(String... args) throws Exception {
            git(concat(new String[]{"commit"}, args));
        }
        String git(String... args) throws Exception {
            Result result = invoke(args);
            if (result.exitCode != 0)
                throw new AssertionError("git " + List.of(args) + " exited " + result.exitCode + ": " + result.output);
            return result.output;
        }
        Result invoke(String... args) throws Exception {
            return process(WRAPPER, args);
        }
        Result invokeDirect(String... args) throws Exception {
            return process(Path.of(REAL_GIT), args);
        }
        Result process(Path executable, String... args) throws Exception {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(executable.toString());
            pb.command().addAll(List.of(args));
            pb.directory(repo.toFile());
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            CONFIG_VARS.forEach(env::remove);
            env.put("HOME", home.toString());
            env.put("XDG_CONFIG_HOME", xdg.toString());
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_CONFIG_GLOBAL", "/dev/null");
            env.put("JUPITER_GIT_REAL", REAL_GIT);
            env.put("LC_ALL", "C");
            env.put("LANG", "C");
            Process p = pb.start();
            boolean finished = p.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new AssertionError("Timed out: " + pb.command());
            }
            return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes()));
        }
    }
}
