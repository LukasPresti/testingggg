package Project.Client.Views;

import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JPanel;

import Project.Client.Client;
import Project.Common.Phase;

public class PlayView extends JPanel {
    private final JPanel buttonPanel = new JPanel();
    private JButton btnRock, btnPaper, btnScissors, btnLizard, btnSpock;

    public PlayView(String name) {
        this.setName(name);

        btnRock = new JButton("Rock");
        btnPaper = new JButton("Paper");
        btnScissors = new JButton("Scissors");
        btnLizard = new JButton("Lizard");
        btnSpock = new JButton("Spock");

        btnRock.addActionListener(_ -> sendChoice("r"));
        btnPaper.addActionListener(_ -> sendChoice("p"));
        btnScissors.addActionListener(_ -> sendChoice("s"));
        btnLizard.addActionListener(_ -> sendChoice("l"));
        btnSpock.addActionListener(_ -> sendChoice("sp"));

        buttonPanel.add(btnRock);
        buttonPanel.add(btnPaper);
        buttonPanel.add(btnScissors);
        buttonPanel.add(btnLizard);
        buttonPanel.add(btnSpock);

        this.add(buttonPanel);

        // Listen for settings updates to refresh UI
        Client.INSTANCE.registerCallback(new Project.Client.Interfaces.ISettingsEvent() {
            @Override
            public void onReceiveSettings(String key, String value) {
                javax.swing.SwingUtilities.invokeLater(() -> updateButtons());
            }
        });
    }

    private void sendChoice(String choice) {
        try {
            Client.INSTANCE.sendDoTurn(choice);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void changePhase(Phase phase) {
        if (phase == Phase.READY) {
            buttonPanel.setVisible(false);
        } else if (phase == Phase.IN_PROGRESS) {
            buttonPanel.setVisible(true);
            updateButtons();
        }
    }

    private void updateButtons() {
        boolean rps5 = Client.INSTANCE.isRPS5();

        btnLizard.setVisible(rps5);
        btnSpock.setVisible(rps5);

        // Cooldown logic could technically be here too, but cooldown is usually
        // per-turn.
        // For simplicity, we just enable the specific buttons allowed by mode.
        // Server will enforce cooldown rejection logic.
        // Or we could track last choice locally in Client to disable specific button.
    }

}
