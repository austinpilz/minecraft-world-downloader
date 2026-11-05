package proxy.accounts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocksPoolTest {
    @Test
    void usesSharedCredentialsAndDefaultPort() {
        SocksPool.ParseResult result = SocksPool.parse("amsterdam.nl.socks.nordhold.net\nus.socks.nordhold.net:1081\n", "svc", "secret");

        assertThat(result.errors()).isEmpty();
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).key()).isEqualTo("amsterdam.nl.socks.nordhold.net:1080");
        assertThat(result.entries().get(0).username()).isEqualTo("svc");
        assertThat(result.entries().get(0).password()).isEqualTo("secret");
        assertThat(result.entries().get(1).port()).isEqualTo(1081);
    }

    @Test
    void lineCredentialsOverrideTheSharedOnesAndMayContainAnAt() {
        SocksPool.Entry entry = SocksPool.parse("socks5://me:p@ss:word@proxy.example:9050", "svc", "secret").entries().get(0);

        assertThat(entry.username()).isEqualTo("me");
        assertThat(entry.password()).isEqualTo("p@ss:word");
        assertThat(entry.host()).isEqualTo("proxy.example");
        assertThat(entry.port()).isEqualTo(9050);
    }

    @Test
    void skipsBlankAndCommentLines() {
        SocksPool.ParseResult result = SocksPool.parse("# nord\n\n  \nhost.example\n", "u", "p");

        assertThat(result.entries()).hasSize(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsBadLinesAndKeepsTheGoodOnes() {
        SocksPool.ParseResult result = SocksPool.parse("ok.example\nbad.example:notaport\nbad.example:99999\nhttp://x/y\nok.example:1080\n", "u", "p");

        assertThat(result.entries()).extracting(SocksPool.Entry::key).containsExactly("ok.example:1080");
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors().get(0)).startsWith("Line 2:");
        assertThat(result.errors().get(3)).contains("listed twice");
    }

    @Test
    void ipv6NeedsBrackets() {
        assertThat(SocksPool.parse("[::1]:1080", "", "").entries().get(0).host()).isEqualTo("::1");
        assertThat(SocksPool.parse("::1", "", "").errors()).hasSize(1);
    }

    @Test
    void entryNeverPrintsItsPassword() {
        SocksPool.Entry entry = SocksPool.parse("host.example", "user", "hunter2").entries().get(0);

        assertThat(entry.toString()).doesNotContain("hunter2").doesNotContain("user");
    }
}
