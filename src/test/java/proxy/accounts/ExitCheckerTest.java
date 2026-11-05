package proxy.accounts;

import java.util.List;
import org.junit.jupiter.api.Test;
import proxy.SocksProxyDetails;

import static org.assertj.core.api.Assertions.assertThat;

class ExitCheckerTest {
    @Test
    void directBotsAreNotChecked() {
        ExitChecker.Result result = new ExitChecker(List.of()).check(null);

        assertThat(result.ok()).isTrue();
        assertThat(result.ip()).isEqualTo(ExitChecker.DIRECT);
    }

    @Test
    void asksTheEchoServiceThroughTheProxyWithItsCredentials() throws Exception {
        try (TestHttpServer echo = new TestHttpServer("203.0.113.7\n", 204);
             TestSocks5Server socks = new TestSocks5Server("svc", "pw")) {
            SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", socks.port(), "svc", "pw").pin();

            ExitChecker.Result result = new ExitChecker(List.of("http://127.0.0.1:" + echo.port() + "/")).check(exit);

            assertThat(result.error()).isNull();
            assertThat(result.ip()).isEqualTo("203.0.113.7");
            assertThat(socks.targets).containsExactly("127.0.0.1:" + echo.port());
            assertThat(socks.loggedInUsers).containsExactly("svc");
        }
    }

    @Test
    void fallsBackToTheNextEchoServiceWhenTheFirstIsNotAnIp() throws Exception {
        try (TestHttpServer bad = new TestHttpServer("<html>nope</html>", 204);
             TestHttpServer good = new TestHttpServer("198.51.100.9", 204);
             TestSocks5Server socks = new TestSocks5Server("svc", "pw")) {
            SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", socks.port(), "svc", "pw").pin();

            ExitChecker.Result result = new ExitChecker(List.of(
                "http://127.0.0.1:" + bad.port() + "/", "http://127.0.0.1:" + good.port() + "/")).check(exit);

            assertThat(result.ip()).isEqualTo("198.51.100.9");
        }
    }

    @Test
    void reportsADeadProxy() throws Exception {
        int deadPort;
        try (TestSocks5Server socks = new TestSocks5Server("svc", "pw")) {
            deadPort = socks.port();
        }
        SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", deadPort, "svc", "pw").pin();

        ExitChecker.Result result = new ExitChecker(List.of("http://127.0.0.1:9/")).check(exit);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void reportsBadCredentialsWithoutLeakingThem() throws Exception {
        try (TestHttpServer echo = new TestHttpServer("203.0.113.7", 204);
             TestSocks5Server socks = new TestSocks5Server("svc", "pw")) {
            SocksProxyDetails exit = new SocksProxyDetails("127.0.0.1", socks.port(), "svc", "wrong-password").pin();

            ExitChecker.Result result = new ExitChecker(List.of("http://127.0.0.1:" + echo.port() + "/")).check(exit);

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).doesNotContain("wrong-password");
            assertThat(socks.rejectedUsers).isNotEmpty();
        }
    }
}
