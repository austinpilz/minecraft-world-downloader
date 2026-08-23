package gui;

import config.Config;
import gui.components.DefaultIntField;
import java.util.Arrays;
import java.util.List;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class ProxyTabController {
    public CheckBox enableSocksProxy;
    public TextField socksProxyHost;
    public DefaultIntField socksProxyPort;
    public TextField socksProxyUsername;
    public PasswordField socksProxyPassword;
    public CheckBox disableSrvLookup;

    @FXML
    public void initialize() {
        Config config = GuiManager.getConfig();

        enableSocksProxy.setSelected(config.enableSocksProxy);
        socksProxyHost.setText(config.socksProxyHost);
        socksProxyPort.setText("" + config.socksProxyPort);
        socksProxyUsername.setText(config.socksProxyUsername);
        socksProxyPassword.setText(config.socksProxyPassword);
        disableSrvLookup.setSelected(config.disableSrvLookup);

        if (config.isStarted()) {
            // the connection details are built once during startup, so these can't take effect anymore
            allControls().forEach(control -> control.setDisable(true));
            return;
        }

        // grey out the proxy details when the proxy isn't in use
        BooleanBinding disabled = enableSocksProxy.selectedProperty().not();
        socksProxyHost.disableProperty().bind(disabled);
        socksProxyPort.disableProperty().bind(disabled);
        socksProxyUsername.disableProperty().bind(disabled);
        socksProxyPassword.disableProperty().bind(disabled);
    }

    private List<Control> allControls() {
        return Arrays.asList(
            enableSocksProxy, socksProxyHost, socksProxyPort, socksProxyUsername, socksProxyPassword,
            disableSrvLookup
        );
    }

    public void save(Config config) {
        config.enableSocksProxy = enableSocksProxy.isSelected();
        config.socksProxyHost = socksProxyHost.getText().trim();
        config.socksProxyPort = Math.abs(socksProxyPort.getAsInt());
        config.socksProxyUsername = socksProxyUsername.getText().trim();
        config.socksProxyPassword = socksProxyPassword.getText();
        config.disableSrvLookup = disableSrvLookup.isSelected();
    }

    public void opened(GuiSettings guiSettings) { }
}
