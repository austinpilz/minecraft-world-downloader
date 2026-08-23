package config;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;
import org.kohsuke.args4j.spi.StringOptionHandler;

/**
 * Handler for the SOCKS proxy argument. Accepts either 'host' or 'host:port' so that the command-line only
 * needs a single option, while the settings GUI keeps the host and the port as separate fields. Passing the
 * argument at all enables the proxy, the same way passing -s implies the settings GUI should be skipped.
 */
public class SocksProxyHandler extends StringOptionHandler {
    public SocksProxyHandler(CmdLineParser parser, OptionDef option, Setter<? super String> setter) {
        super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
        String value = params.getParameter(0);

        if (value == null || value.isEmpty()) {
            return super.parseArguments(params);
        }

        String host = value;
        int port = 0;

        if (value.contains(":")) {
            String[] parts = value.split(":", 2);
            host = parts[0];

            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                throw new CmdLineException(owner, "Invalid SOCKS proxy port: " + parts[1], ex);
            }
        }

        setter.addValue(host);
        Config.enableSocksProxy(port);

        return 1;
    }
}
