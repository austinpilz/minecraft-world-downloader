package gui;

import config.Config;
import gui.components.IntField;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class ProxyTabController {
    public CheckBox enabled;
    public TextField host;
    public IntField port;
    public TextField username;
    public PasswordField password;

    @FXML
    void initialize() {
        Config config = GuiManager.getConfig();

        enabled.setSelected(config.socksProxyEnabled);
        host.setText(config.socksProxyHost);
        port.setValue(config.socksProxyPort);
        username.setText(config.socksProxyUsername);
        password.setText(config.socksProxyPassword);

        updateFieldState();
        enabled.selectedProperty().addListener((ov, oldVal, newVal) -> updateFieldState());

        // the proxy is only read once, when the connection is first established, so it can't be
        // changed for a session that's already running
        if (config.isStarted()) {
            enabled.setDisable(true);
        }
    }

    private void updateFieldState() {
        boolean isEnabled = enabled.isSelected() && !enabled.isDisabled();
        host.setDisable(!isEnabled);
        port.setDisable(!isEnabled);
        username.setDisable(!isEnabled);
        password.setDisable(!isEnabled);
    }

    public void save() {
        Config config = GuiManager.getConfig();

        config.socksProxyEnabled = enabled.isSelected();
        config.socksProxyHost = host.getText();
        config.socksProxyPort = port.getAsInt();
        config.socksProxyUsername = username.getText();
        config.socksProxyPassword = password.getText();
    }
}
