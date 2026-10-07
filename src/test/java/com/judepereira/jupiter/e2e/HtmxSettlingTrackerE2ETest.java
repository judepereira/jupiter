package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HtmxSettlingTrackerE2ETest extends E2ETestSupport {

    @Test
    void settlesAfterDelayedOobReplacementThenPreservesLateCustomName() throws Exception {
        try (TestHttpServer server = new TestHttpServer(); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            installHtmxSettlingTracker(page);
            page.navigate(server.baseUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.evaluate("""
                    (url) => {
                      document.body.innerHTML = `<input id="project-name-input" value="Sample App">
                        <input id="project-path-input" value="/sample-app">
                        <button id="load" hx-get="${url}/test/oob" hx-trigger="click" hx-swap="none">load</button>`;
                      htmx.process(document.body);
                    }
                    """, server.baseUrl());

            page.locator("#load").click();
            Assertions.assertTrue(server.oobRequestStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertTrue(server.oobReleasePending());
            page.evaluate("url => setTimeout(() => fetch(`${url}/release-oob`), 200)", server.baseUrl());
            settlePage(page);

            Assertions.assertEquals("Server Name", page.locator("#project-name-input").inputValue());
            Assertions.assertEquals("/server-path", page.locator("#project-path-input").inputValue());
            page.locator("#project-name-input").fill("Custom Service");
            settlePage(page);
            Assertions.assertEquals("Custom Service", page.locator("#project-name-input").inputValue());
        }
    }

    @Test
    void waitsForDelayedSwapAndSettleMarkers() throws Exception {
        try (TestHttpServer server = new TestHttpServer(); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            installHtmxSettlingTracker(page);
            page.navigate(server.baseUrl());
            page.evaluate("(url) => { document.body.innerHTML = "
                    + "`<div id='result' hx-get='${url}/test/delayed-swap' hx-trigger='load' "
                    + "hx-swap='outerHTML swap:150ms settle:200ms'>loading</div>`; htmx.process(document.body); }",
                    server.baseUrl());
            settlePage(page);
            Assertions.assertEquals("replaced", page.locator("#result").textContent());
            Assertions.assertEquals(0, page.locator("#result.htmx-swapping, #result.htmx-settling").count());
        }
    }

    @Test
    void duplicateAfterRequestDoesNotForgetAnotherActiveRequest() throws Exception {
        try (TestHttpServer server = new TestHttpServer(); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            installHtmxSettlingTracker(page);
            page.navigate(server.baseUrl());
            page.evaluate("() => { document.body.innerHTML = '<div id=ready></div>'; htmx.process(document.body); "
                    + "document.addEventListener('htmx:afterRequest', e => { if (e.detail.xhr === window.second) "
                    + "document.querySelector('#ready').dataset.secondComplete = 'true'; }); }");
            page.evaluate("""
                    () => {
                      const first = {};
                      const second = {};
                      window.second = second;
                      const emit = (name, xhr) => document.dispatchEvent(new CustomEvent(name, {detail: {xhr}}));
                      emit('htmx:beforeRequest', first);
                      emit('htmx:beforeRequest', second);
                      emit('htmx:afterRequest', first);
                      emit('htmx:afterRequest', first);
                      setTimeout(() => emit('htmx:afterRequest', second), 100);
                    }
                    """);
            settlePage(page);
            Assertions.assertEquals("true", page.locator("#ready").getAttribute("data-second-complete"));
        }
    }

    @Test
    void tracksRequestsStartedByInitialNavigation() throws Exception {
        try (TestHttpServer server = new TestHttpServer(); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            installHtmxSettlingTracker(page);
            page.navigate(server.baseUrl() + "/test/navigation",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Assertions.assertTrue(server.initialRequestStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertTrue(server.initialReleasePending());
            page.evaluate("() => setTimeout(() => fetch('/release-initial'), 200)");
            settlePage(page);
            Assertions.assertEquals("ready", page.locator("#result").textContent());
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch oobRelease = new CountDownLatch(1);
        private final CountDownLatch initialRelease = new CountDownLatch(1);
        final CountDownLatch oobRequestStarted = new CountDownLatch(1);
        final CountDownLatch initialRequestStarted = new CountDownLatch(1);

        TestHttpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::root);
            server.createContext("/htmx.js", this::htmx);
            server.createContext("/test/oob",
                    exchange -> delayed(exchange, oobRequestStarted, oobRelease,
                            "<input id='project-name-input' value='Server Name' hx-swap-oob='outerHTML'>"
                                    + "<input id='project-path-input' value='/server-path' hx-swap-oob='outerHTML'>"));
            server.createContext("/release-oob", exchange -> release(exchange, oobRelease));
            server.createContext("/test/initial-request",
                    exchange -> delayed(exchange, initialRequestStarted, initialRelease, "ready"));
            server.createContext("/release-initial", exchange -> release(exchange, initialRelease));
            server.createContext("/test/delayed-swap",
                    exchange -> respond(exchange, "<div id='result'>replaced</div>"));
            server.createContext("/test/navigation", this::navigation);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        boolean oobReleasePending() {
            return oobRelease.getCount() == 1;
        }

        boolean initialReleasePending() {
            return initialRelease.getCount() == 1;
        }

        private void root(HttpExchange exchange) throws IOException {
            respond(exchange, "<script src='/htmx.js'></script><div id='root'></div>");
        }

        private void navigation(HttpExchange exchange) throws IOException {
            respond(exchange, "<script src='/htmx.js'></script>"
                    + "<div id='result' hx-get='/test/initial-request' hx-trigger='load'>loading</div>");
        }

        private void htmx(HttpExchange exchange) throws IOException {
            try (InputStream stream = HtmxSettlingTrackerE2ETest.class
                    .getResourceAsStream("/META-INF/resources/webjars/htmx.org/1.9.4/dist/htmx.min.js")) {
                if (stream == null) {
                    throw new IOException("HTMX WebJar resource is missing");
                }
                respond(exchange, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        private static void delayed(HttpExchange exchange, CountDownLatch started, CountDownLatch release, String body)
                throws IOException {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for browser release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for browser release", exception);
            }
            respond(exchange, body);
        }

        private static void release(HttpExchange exchange, CountDownLatch latch) throws IOException {
            latch.countDown();
            respond(exchange, "released");
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (exchange) {
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
