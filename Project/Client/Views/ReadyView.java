package Project.Client.Views;

import java.io.IOException;
import javax.swing.JButton;
import javax.swing.JPanel;
import Project.Client.Client;

import Project.Client.Interfaces.IRoomEvents;
import java.util.List;

public class ReadyView extends JPanel implements IRoomEvents {
    private javax.swing.JCheckBox chkRPS5;
    private javax.swing.JCheckBox chkRPS5Final3;
    private javax.swing.JCheckBox chkCooldown;

    public ReadyView() {
        this.setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        Client.INSTANCE.registerCallback(this);

        JButton readyButton = new JButton("Ready");
        readyButton.addActionListener(_ -> {
            try {
                Client.INSTANCE.sendReady();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        });
        this.add(readyButton);

        // Host Toggles
        chkRPS5 = new javax.swing.JCheckBox("Enable RPS-5");
        chkRPS5.addActionListener(_ -> {
            boolean selected = chkRPS5.isSelected();
            sendSetting("rps5", selected);
            // Hide/Reset Final3 if RPS5 is disabled
            if (!selected) {
                if (chkRPS5Final3.isSelected()) {
                    chkRPS5Final3.setSelected(false);
                    sendSetting("rps5_final3", false);
                }
                chkRPS5Final3.setVisible(false);
            } else {
                chkRPS5Final3.setVisible(Client.INSTANCE.isHost());
            }
            this.revalidate();
            this.repaint();
        });
        this.add(chkRPS5);

        chkRPS5Final3 = new javax.swing.JCheckBox("Final 3 Only");
        chkRPS5Final3.addActionListener(_ -> sendSetting("rps5_final3", chkRPS5Final3.isSelected()));
        chkRPS5Final3.setVisible(false); // Initially hidden
        this.add(chkRPS5Final3);

        chkCooldown = new javax.swing.JCheckBox("Enable Cooldowns");
        chkCooldown.addActionListener(_ -> sendSetting("cooldown", chkCooldown.isSelected()));
        this.add(chkCooldown);

        // Initial state check
        updateHostControls();
    }

    private void updateHostControls() {
        boolean isHost = Client.INSTANCE.isHost();
        chkRPS5.setVisible(isHost);
        // Only show Final 3 option if Host AND RPS5 is enabled
        chkRPS5Final3.setVisible(isHost && chkRPS5.isSelected());
        chkCooldown.setVisible(isHost);
        this.revalidate();
        this.repaint();
    }

    @Override
    public void onReceiveHost(long hostId) {
        updateHostControls();
    }

    private void sendSetting(String key, boolean enabled) {
        try {
            Client.INSTANCE.sendMessage(String.format("/settings %s %s", key, enabled ? "on" : "off"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onReceiveRoomList(List<String> rooms, String message) {
    }

    @Override
    public void onRoomAction(long clientId, String roomName, boolean isJoin, boolean isQuiet) {
    }
}
