package Project.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.Payload;
import Project.Common.PayloadType;
import Project.Common.Phase;
import Project.Common.TimedEvent;
import Project.Common.TimerType;
import Project.Exceptions.NotReadyException;
import Project.Exceptions.PhaseMismatchException;
import Project.Exceptions.PlayerNotFoundException;

public class GameRoom extends BaseGameRoom {

    // used for general rounds (usually phase-based turns)
    private TimedEvent roundTimer = null;
    private int round = 0;

    // Feature toggles
    private boolean enableRPS5 = false;
    private boolean enableRPS5Final3 = false;
    private boolean enableCooldown = false;

    public GameRoom(String name) {
        super(name);
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientAdded(ServerThread sp) {
        // sync GameRoom state to new client
        syncCurrentPhase(sp);
        syncReadyStatus(sp);
        if (currentPhase != Phase.READY) {
            syncPlayerPoints(sp);
        }
        // Sync settings to new client
        sp.sendToClient(createSettingsPayload("rps5", enableRPS5 ? "on" : "off"));
        sp.sendToClient(createSettingsPayload("rps5_final3", enableRPS5Final3 ? "on" : "off"));
        sp.sendToClient(createSettingsPayload("cooldown", enableCooldown ? "on" : "off"));
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientRemoved(ServerThread sp) {
        LoggerUtil.INSTANCE.info("Player Removed, remaining: " + clientsInRoom.size());
        if (clientsInRoom.isEmpty()) {
            resetReadyTimer();
            resetRoundTimer();
            onSessionEnd();
        } else {
            // If a player leaves during a round, we might need to check end condition
            // For now, simpler handling
            if (currentPhase == Phase.IN_PROGRESS) {
                checkAllPicked();
            }
        }
    }

    // timer handlers
    private void startRoundTimer() {
        roundTimer = new TimedEvent(30, () -> onRoundEnd());
        roundTimer.setTickCallback((time) -> {
            sendCurrentTime(TimerType.ROUND, time);
        });
    }

    private void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
            sendCurrentTime(TimerType.ROUND, -1);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onSessionStart() {
        LoggerUtil.INSTANCE.info("onSessionStart() start");
        changePhase(Phase.IN_PROGRESS);
        round = 0;
        // reset elimination status and lastChoice
        clientsInRoom.values().forEach(client -> {
            client.setEliminated(false);
            client.setLastChoice(null);
        });
        LoggerUtil.INSTANCE.info("onSessionStart() end");
        onRoundStart();
    }

    /** {@inheritDoc} */
    @Override
    protected void onRoundStart() {
        LoggerUtil.INSTANCE.info("onRoundStart() start");
        resetRoundTimer();
        // Reset choices for active players
        clientsInRoom.values().forEach(client -> {
            client.setChoice(null);
            client.setTookTurn(false); // Using tookTurn to track if they picked
        });
        sendResetTurnStatus(); // Clears visuals on client

        round++;
        sendGameEvent(String.format("Round %d has started", round));
        startRoundTimer();
        LoggerUtil.INSTANCE.info("onRoundStart() end");
    }

    /** {@inheritDoc} */
    @Override
    protected void onRoundEnd() {
        LoggerUtil.INSTANCE.info("onRoundEnd() start");
        resetRoundTimer();

        // Update last choice for cooldowns (before clearing)
        clientsInRoom.values().forEach(client -> {
            if (client.getChoice() != null) {
                client.setLastChoice(client.getChoice());
            }
        });

        // Eliminate those who didn't pick (SKIP Away/Spectators)
        clientsInRoom.values().stream()
                .filter(client -> !client.isEliminated() && client.getChoice() == null && !client.isAway()
                        && !client.isSpectator())
                .forEach(client -> {
                    client.setEliminated(true);
                    sendGameEvent(client.getDisplayName() + " eliminated (did not pick)");
                });

        // Process Battles
        processBattles();

        // Check Game Over
        long activeCount = clientsInRoom.values().stream()
                .filter(c -> !c.isEliminated() && !c.isSpectator())
                .count();
        if (activeCount == 1) {
            ServerThread winner = clientsInRoom.values().stream()
                    .filter(c -> !c.isEliminated() && !c.isSpectator())
                    .findFirst()
                    .orElse(null);
            sendGameEvent("Game Over! Winner: " + (winner != null ? winner.getDisplayName() : "Unknown"));
            onSessionEnd();
        } else if (activeCount == 0 && clientsInRoom.size() > 0) { // Only game over if players existed
            sendGameEvent("Game Over! It's a Tie (No survivors)");
            onSessionEnd();
        } else {
            onRoundStart();
        }

        LoggerUtil.INSTANCE.info("onRoundEnd() end");
    }

    private void processBattles() {
        List<ServerThread> activePlayers = clientsInRoom.values().stream()
                .filter(c -> !c.isEliminated() && c.getChoice() != null && !c.isAway() && !c.isSpectator())
                .collect(Collectors.toList());

        if (activePlayers.size() < 2) {
            return; // Needs at least 2 to battle
        }

        // Round-robin: i vs (i+1)%size
        StringBuilder battleLog = new StringBuilder("Battle Results:\n");
        // Track stats to apply after all checks
        List<ServerThread> eliminatedThisRound = new ArrayList<>();

        for (int i = 0; i < activePlayers.size(); i++) {
            ServerThread attacker = activePlayers.get(i);
            ServerThread defender = activePlayers.get((i + 1) % activePlayers.size());

            int result = compareChoices(attacker.getChoice(), defender.getChoice());

            String logLine = String.format("%s(%s) vs %s(%s): ",
                    attacker.getDisplayName(), attacker.getChoice(),
                    defender.getDisplayName(), defender.getChoice());

            if (result == 1) { // Attacker Wins
                logLine += attacker.getDisplayName() + " Wins!";
                attacker.changePoints(1);
                eliminatedThisRound.add(defender);
            } else if (result == -1) { // Defender Wins
                logLine += defender.getDisplayName() + " Wins!";
                defender.changePoints(1);
                eliminatedThisRound.add(attacker);
            } else {
                logLine += "Tie!";
            }
            battleLog.append(logLine).append("\n");
        }

        sendGameEvent(battleLog.toString());
        syncPlayerPoints(null); // Sync all

        // Apply eliminations
        eliminatedThisRound.forEach(client -> {
            if (!client.isEliminated()) { // Prevent double elimination msg
                client.setEliminated(true);
                sendGameEvent(client.getDisplayName() + " has been eliminated!");
            }
        });
    }

    // 1 = p1 wins, -1 = p2 wins, 0 = tie
    // 1 = p1 wins, -1 = p2 wins, 0 = tie
    private int compareChoices(String p1, String p2) {
        if (p1.equals(p2))
            return 0;
        if (p1.equals("r"))
            return (p2.equals("s") || p2.equals("l")) ? 1 : -1;
        if (p1.equals("p"))
            return (p2.equals("r") || p2.equals("sp")) ? 1 : -1;
        if (p1.equals("s"))
            return (p2.equals("p") || p2.equals("l")) ? 1 : -1;
        if (p1.equals("l"))
            return (p2.equals("sp") || p2.equals("p")) ? 1 : -1;
        if (p1.equals("sp"))
            return (p2.equals("s") || p2.equals("r")) ? 1 : -1;
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    protected void onSessionEnd() {
        LoggerUtil.INSTANCE.info("onSessionEnd() start");
        resetReadyStatus();
        resetTurnStatus();
        changePhase(Phase.READY);
        // Show scoreboard
        List<ServerThread> sorted = clientsInRoom.values().stream()
                .sorted((a, b) -> Integer.compare(b.getPoints(), a.getPoints()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder("Final Scoreboard:\n");
        for (ServerThread p : sorted) {
            sb.append(String.format("%s: %d\n", p.getDisplayName(), p.getPoints()));
        }
        sendGameEvent(sb.toString());

        // Reset points for all clients logic (Server-side)
        clientsInRoom.values().forEach(client -> client.setPoints(0));

        LoggerUtil.INSTANCE.info("onSessionEnd() end");
    }

    // No longer using onTurnStart/onTurnEnd for simultaneous logic
    @Override
    protected void onTurnStart() {
    }

    @Override
    protected void onTurnEnd() {
    }

    // send/sync data to ServerThread(s)
    private void syncPlayerPoints(ServerThread incomingClient) {
        // If incoming is null, sync everyone
        if (incomingClient == null) {
            clientsInRoom.values().forEach(client -> sendPlayerPoints(client));
            return;
        }
        clientsInRoom.values().forEach(serverUser -> {
            if (serverUser.getClientId() != incomingClient.getClientId()) {
                incomingClient.sendPlayerPoints(serverUser.getClientId(), serverUser.getPoints());
            }
        });
    }

    private void sendPlayerPoints(ServerThread sp) {
        clientsInRoom.values().forEach(spInRoom -> {
            spInRoom.sendPlayerPoints(sp.getClientId(), sp.getPoints());
        });
    }

    private void sendResetTurnStatus() {
        clientsInRoom.values().forEach(spInRoom -> {
            spInRoom.sendResetTurnStatus();
        });
    }

    private void sendTurnStatus(ServerThread client, boolean tookTurn) {
        clientsInRoom.values().forEach(spInRoom -> {
            spInRoom.sendTurnStatus(client.getClientId(), client.didTakeTurn());
        });
    }

    // misc methods
    private void resetTurnStatus() {
        clientsInRoom.values().forEach(sp -> {
            sp.setTookTurn(false);
            sp.setChoice(null);
        });
        sendResetTurnStatus();
    }

    private void checkAllPicked() {
        long activeCount = clientsInRoom.values().stream()
                .filter(sp -> !sp.isEliminated() && !sp.isAway() && !sp.isSpectator())
                .count();
        long pickedCount = clientsInRoom.values().stream()
                .filter(sp -> !sp.isEliminated() && sp.getChoice() != null)
                .count();

        if (pickedCount >= activeCount && activeCount > 0) {
            sendGameEvent("All active players have picked!");
            onRoundEnd();
        }
    }

    // receive data from ServerThread (GameRoom specific)

    @Override
    protected void handleReady(ServerThread sender) {
        super.handleReady(sender);
        if (sender.isSpectator()) {
            // Spectators can't be ready or influence start
            sender.setReady(false);
            return;
        }
        // Custom check to start early if everyone is ready
        long numReady = clientsInRoom.values().stream()
                .filter(p -> p.isReady())
                .count();
        // Fix: Use total clients in room to determine if "Everyone" is ready.
        // Old logic filtered out spectators (!isReady) causing numReady == numPlayers
        // loop.
        long numPlayers = clientsInRoom.size();

        if (numReady == numPlayers && numReady >= MINIMUM_REQUIRED_TO_START) {
            resetReadyTimer(); // cancel the 30s timer
            onSessionStart();
        }
    }

    /**
     * Handles the pick action from the client.
     */
    protected void handleTurnAction(ServerThread currentUser, String choice) {
        // check if the client is in the room
        try {
            checkPlayerInRoom(currentUser);
            checkCurrentPhase(currentUser, Phase.IN_PROGRESS);
            // checkCurrentPlayer(currentUser.getClientId()); // removed turn check
            checkIsReady(currentUser); // Should be ready if in game, or maybe isEliminated check?

            if (currentUser.isEliminated()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You are eliminated and cannot pick.");
                return;
            }

            if (currentUser.getChoice() != null) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                        "You have already picked " + currentUser.getChoice());
                return;
            }

            if (currentUser.isSpectator()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "Spectators cannot play.");
                return;
            }

            String c = choice.trim().toLowerCase();
            // Validate against RPS5
            boolean isRPS5Allowed = enableRPS5;
            if (isRPS5Allowed && enableRPS5Final3) {
                long activeCount = clientsInRoom.values().stream()
                        .filter(p -> !p.isSpectator() && !p.isAway() && !p.isEliminated())
                        .count();
                LoggerUtil.INSTANCE.info(String.format("RPS5 Final 3 Check: Active=%d, Total=%d, Enabled=%s",
                        activeCount, clientsInRoom.size(), enableRPS5Final3));

                if (activeCount > 3) {
                    isRPS5Allowed = false;
                }
            }

            if (!isRPS5Allowed && (c.equals("l") || c.equals("sp"))) {
                String msg = "RPS-5 is disabled.";
                if (enableRPS5 && enableRPS5Final3) {
                    msg += " (Wait for Final 3)";
                }
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, msg);
                return;
            }

            // Validate Cooldown
            if (enableCooldown && currentUser.getLastChoice() != null && currentUser.getLastChoice().equals(c)) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                        String.format("Cooldown enabled. You cannot pick %s again.", c));
                return;
            }

            // String c = choice.trim().toLowerCase(); // removed duplicate
            // Simple mapping/validation
            if (c.startsWith("r"))
                c = "r";
            else if (c.startsWith("p"))
                c = "p";
            else if (c.startsWith("s"))
                c = "s";
            else if (c.startsWith("l"))
                c = "l";
            else if (c.startsWith("sp"))
                c = "sp";
            else {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "Invalid choice. Use r, p, s, l, or sp.");
                return;
            }

            // Cooldown check
            if (enableCooldown && c.equals(currentUser.getLastChoice())) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                        "Cooldown active: You cannot pick the same option twice in a row!");
                return;
            }

            currentUser.setChoice(c);
            currentUser.setTookTurn(true); // Visually marks them as "Done"

            sendGameEvent(String.format("%s picked their choice", currentUser.getDisplayName()));
            sendTurnStatus(currentUser, true); // Sync visual

            checkAllPicked();

        } catch (NotReadyException e) {
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (PhaseMismatchException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You can only pick during the IN_PROGRESS phase");
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        }
    }
    // end receive data from ServerThread (GameRoom specific)

    private Payload createSettingsPayload(String key, String value) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.SETTINGS);
        p.setMessage(key + " " + value);
        return p;
    }

    private void sendSettings(String key, String value) {
        clientsInRoom.values().forEach(c -> c.sendToClient(createSettingsPayload(key, value)));
    }

    public void handleSettings(ServerThread sender, String command) {
        if (sender.getClientId() != hostId && !command.startsWith("away") && !command.startsWith("spectator")) {
            sender.sendMessage(Constants.DEFAULT_CLIENT_ID, "Only the host can change game settings.");
            return;
        }

        String[] parts = command.split(" ");
        String key = parts[0].toLowerCase();

        switch (key) {
            case "rps5":
                if (parts.length > 1) {
                    enableRPS5 = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
                    sendSettings("rps5", enableRPS5 ? "on" : "off");
                    sendGameEvent("RPS-5 " + (enableRPS5 ? "Enabled" : "Disabled"));
                }
                break;
            case "rps5_final3":
                if (parts.length > 1) {
                    enableRPS5Final3 = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
                    sendSettings("rps5_final3", enableRPS5Final3 ? "on" : "off");
                    sendGameEvent("RPS-5 Final 3 Mode " + (enableRPS5Final3 ? "Enabled" : "Disabled"));
                }
                break;
            case "cooldown":
                if (parts.length > 1) {
                    enableCooldown = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
                    sendSettings("cooldown", enableCooldown ? "on" : "off");
                    sendGameEvent("Cooldown " + (enableCooldown ? "Enabled" : "Disabled"));
                }
                break;
            case "away":
                boolean isAway = !sender.isAway(); // Default toggle
                if (parts.length > 1) {
                    isAway = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
                }
                sender.setAway(isAway);
                sendGameEvent(sender.getDisplayName() + (isAway ? " is now Away" : " is no longer Away"));
                // Broadcast update to all clients
                clientsInRoom.values().forEach(client -> {
                    client.sendClientInfo(sender.getClientId(), sender.getClientName(), getName(),
                            Project.Common.RoomAction.JOIN, true, sender.isAway());
                });
                break;
            default:
                sender.sendMessage(Constants.DEFAULT_CLIENT_ID, "Unknown setting: " + key);
                break;
        }
    }
}