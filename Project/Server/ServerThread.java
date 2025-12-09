package Project.Server;

import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import Project.Common.TextFX.Color;
import Project.Common.TimerPayload;
import Project.Common.TimerType;
import Project.Common.ConnectionPayload;
import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.Payload;
import Project.Common.PayloadType;
import Project.Common.Phase;
import Project.Common.PointsPayload;
import Project.Common.ReadyPayload;
import Project.Common.RoomAction;
import Project.Common.RoomResultPayload;
import Project.Common.TextFX;

/**
 * A server-side representation of a single client
 */
public class ServerThread extends BaseServerThread {
    private Consumer<ServerThread> onInitializationComplete; // callback to inform when this object is ready

    /**
     * A wrapper method so we don't need to keep typing out the long/complex sysout
     * line inside
     * 
     * @param message
     */
    @Override
    protected void info(String message) {
        LoggerUtil.INSTANCE
                .info(TextFX.colorize(String.format("Thread[%s]: %s", this.getClientId(), message), Color.CYAN));
    }

    /**
     * Wraps the Socket connection and takes a Server reference and a callback
     * 
     * @param myClient
     * @param server
     * @param onInitializationComplete method to inform listener that this object is
     *                                 ready
     */
    protected ServerThread(Socket myClient, Consumer<ServerThread> onInitializationComplete) {
        Objects.requireNonNull(myClient, "Client socket cannot be null");
        Objects.requireNonNull(onInitializationComplete, "callback cannot be null");
        info("ServerThread created");
        // get communication channels to single client
        this.client = myClient;
        // this.clientId = this.threadId(); // An id associated with the thread
        // instance, used as a temporary identifier
        this.onInitializationComplete = onInitializationComplete;

    }

    // Start Send*() Methods
    /**
     * Syncs a specific client's points
     * 
     * @param clientId
     * @param points
     * @return
     */
    public boolean sendPlayerPoints(long clientId, int points) {
        PointsPayload rp = new PointsPayload();
        rp.setPoints(points);
        rp.setClientId(clientId);
        return sendToClient(rp);
    }

    public boolean sendGameEvent(String str) {
        return sendMessage(Constants.GAME_EVENT_CHANNEL, str);
    }

    /**
     * Syncs the current time of a specific TimerType
     * 
     * @param timerType
     * @param time
     * @return
     */
    public boolean sendCurrentTime(TimerType timerType, int time) {
        TimerPayload tp = new TimerPayload();
        tp.setTime(time);
        tp.setTimerType(timerType);
        return sendToClient(tp);
    }

    public boolean sendResetTurnStatus() {
        ReadyPayload rp = new ReadyPayload();
        rp.setPayloadType(PayloadType.RESET_TURN);
        return sendToClient(rp);
    }

    public boolean sendTurnStatus(long clientId, boolean didTakeTurn) {
        return sendTurnStatus(clientId, didTakeTurn, false);
    }

    public boolean sendTurnStatus(long clientId, boolean didTakeTurn, boolean quiet) {
        // NOTE for now using ReadyPayload as it has the necessary properties
        // An actual turn may include other data for your project
        ReadyPayload rp = new ReadyPayload();
        rp.setPayloadType(quiet ? PayloadType.SYNC_TURN : PayloadType.TURN);
        rp.setClientId(clientId);
        rp.setReady(didTakeTurn);
        return sendToClient(rp);
    }

    public boolean sendCurrentPhase(Phase phase) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.PHASE);
        p.setMessage(phase.name());
        return sendToClient(p);
    }

    public boolean sendResetReady() {
        ReadyPayload rp = new ReadyPayload();
        rp.setPayloadType(PayloadType.RESET_READY);
        return sendToClient(rp);
    }

    public boolean sendReadyStatus(long clientId, boolean isReady) {
        return sendReadyStatus(clientId, isReady, false);
    }

    /**
     * Sync ready status of client id
     * 
     * @param clientId who
     * @param isReady  ready or not
     * @param quiet    silently mark ready
     * @return
     */
    public boolean sendReadyStatus(long clientId, boolean isReady, boolean quiet) {
        ReadyPayload rp = new ReadyPayload();
        rp.setClientId(clientId);
        rp.setReady(isReady);
        if (quiet) {
            rp.setPayloadType(PayloadType.SYNC_READY);
        }
        return sendToClient(rp);
    }

    public boolean sendRooms(List<String> rooms) {
        RoomResultPayload rrp = new RoomResultPayload();
        rrp.setRooms(rooms);
        return sendToClient(rrp);
    }

    protected boolean sendDisconnect(long clientId) {
        Payload payload = new Payload();
        payload.setClientId(clientId);
        payload.setPayloadType(PayloadType.DISCONNECT);
        return sendToClient(payload);
    }

    protected boolean sendResetUserList() {
        return sendClientInfo(Constants.DEFAULT_CLIENT_ID, null, null, RoomAction.JOIN);
    }

    /**
     * Syncs Client Info (id, name, join status) to the client
     * 
     * @param clientId   use -1 for reset/clear
     * @param clientName
     * @param action     RoomAction of Join or Leave
     * @return true for successful send
     */
    protected boolean sendClientInfo(long clientId, String clientName, String roomName, RoomAction action) {
        return sendClientInfo(clientId, clientName, roomName, action, false);
    }

    /**
     * Syncs Client Info (id, name, join status) to the client
     * 
     * @param clientId   use -1 for reset/clear
     * @param clientName
     * @param action     RoomAction of Join or Leave
     * @param isSync     True is used to not show output on the client side (silent
     *                   sync)
     * @return true for successful send
     */
    protected boolean sendClientInfo(long clientId, String clientName, String roomName, RoomAction action,
            boolean isSync) {
        ConnectionPayload payload = new ConnectionPayload();
        switch (action) {
            case JOIN:
                payload.setPayloadType(PayloadType.ROOM_JOIN);
                break;
            case LEAVE:
                payload.setPayloadType(PayloadType.ROOM_LEAVE);
                break;
            case HOST:
                payload.setPayloadType(PayloadType.HOST);
                break;
            default:
                break;
        }
        if (isSync) {
            payload.setPayloadType(PayloadType.SYNC_CLIENT);
        }
        payload.setClientId(clientId);
        payload.setClientName(clientName);
        payload.setMessage(roomName);

        // Populate status fields if they match the current user/client we are syncing
        // info about
        // Wait, 'clientId' passed here might not be 'this' client.
        // sendClientInfo is usually called to tell 'this' client about 'clientId'.
        // BUT ServerThread represents a connection to ONE client.
        // If I use sendClientInfo generally, I might be passing another client's info.
        // Implementation check: Game.java calls clientsInRoom.values().forEach(client
        // -> client.sendClientInfo(sender...))
        // So 'sender' is the one whose info is being sent.
        // ServerThread.sendClientInfo takes (clientId, clientName...). It doesn't take
        // the User object.
        // I need to change the method signature or pass the User object or extra flags.
        // It's probably better to overload sendClientInfo or add params.
        // Or since I can't change all calls easily, let's look at how it's used.
        // It's used in Room.java and GameRoom.java.
        // I should probably overload it to take a User object if possible, or just add
        // the booleans.
        return sendToClient(payload);
    }

    // Overload for convenience
    protected boolean sendClientInfo(long clientId, String clientName, String roomName, RoomAction action,
            boolean isSync, boolean isAway) {
        ConnectionPayload payload = new ConnectionPayload();
        switch (action) {
            case JOIN:
                payload.setPayloadType(PayloadType.ROOM_JOIN);
                break;
            case LEAVE:
                payload.setPayloadType(PayloadType.ROOM_LEAVE);
                break;
            case HOST:
                payload.setPayloadType(PayloadType.HOST);
                break;
            default:
                break;
        }
        if (isSync) {
            payload.setPayloadType(PayloadType.SYNC_CLIENT);
        }
        payload.setClientId(clientId);
        payload.setClientName(clientName);
        payload.setMessage(roomName);
        payload.setAway(isAway);
        // payload.setSpectator(isSpectator); // Removed
        return sendToClient(payload);
    }

    /**
     * Sends this client's id to the client.
     * This will be a successfully connection handshake
     * 
     * @return true for successful send
     */
    protected boolean sendClientId() {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_ID);
        payload.setClientId(getClientId());
        payload.setClientName(getClientName());// Can be used as a Server-side override of username (i.e., profanity
                                               // filter)
        return sendToClient(payload);
    }

    /**
     * Sends a message to the client
     * 
     * @param clientId who it's from
     * @param message
     * @return true for successful send
     */
    protected boolean sendMessage(long clientId, String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        payload.setClientId(clientId);
        return sendToClient(payload);
    }

    // End Send*() Methods
    @Override
    protected void processPayload(Payload incoming) {

        switch (incoming.getPayloadType()) {
            case CLIENT_CONNECT:
                setClientName(((ConnectionPayload) incoming).getClientName().trim());

                break;
            case DISCONNECT:
                currentRoom.handleDisconnect(this);
                break;
            case MESSAGE:
                currentRoom.handleMessage(this, incoming.getMessage());
                break;
            case REVERSE:
                currentRoom.handleReverseText(this, incoming.getMessage());
                break;
            case ROOM_CREATE:
                currentRoom.handleCreateRoom(this, incoming.getMessage());
                break;
            case ROOM_JOIN:
                currentRoom.handleJoinRoom(this, incoming.getMessage());
                break;
            case ROOM_LEAVE:
                currentRoom.handleJoinRoom(this, Room.LOBBY);
                break;
            case ROOM_LIST:
                currentRoom.handleListRooms(this, incoming.getMessage());
                break;
            case READY:
                // no data needed as the intent will be used as the trigger
                try {
                    // cast to GameRoom as the subclass will handle all Game logic
                    ((GameRoom) currentRoom).handleReady(this);
                } catch (Exception e) {
                    sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to do the ready check");
                }
                break;
            case TURN:
                // no data needed as the intent will be used as the trigger
                try {
                    // cast to GameRoom as the subclass will handle all Game logic
                    ((GameRoom) currentRoom).handleTurnAction(this, incoming.getMessage());
                } catch (Exception e) {
                    sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to do a turn");
                }
                break;
            case SETTINGS:
                // no data needed as the intent will be used as the trigger
                try {
                    // cast to GameRoom as the subclass will handle all Game logic
                    ((GameRoom) currentRoom).handleSettings(this, incoming.getMessage());
                } catch (Exception e) {
                    sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to change settings");
                }
                break;
            case AWAY:
                try {
                    // reuse existing logic from GameRoom which toggles away status
                    ((GameRoom) currentRoom).handleSettings(this, "away");
                } catch (Exception e) {
                    sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to use the Away feature");
                }
                break;
            default:
                LoggerUtil.INSTANCE.warning(TextFX.colorize("Unknown payload type received", Color.RED));
                break;
        }
    }

    // limited user data exposer
    protected boolean isReady() {
        return this.user.isReady();
    }

    protected void setReady(boolean isReady) {
        this.user.setReady(isReady);
    }

    protected boolean didTakeTurn() {
        return this.user.didTakeTurn();
    }

    protected void setTookTurn(boolean tookTurn) {
        this.user.setTookTurn(tookTurn);
    }

    protected int getPoints() {
        return this.user.getPoints();
    }

    protected void setPoints(int points) {
        this.user.setPoints(points);
    }

    protected void changePoints(int points) {
        this.user.setPoints(this.user.getPoints() + points);
    }

    protected String getChoice() {
        return this.user.getChoice();
    }

    protected void setChoice(String choice) {
        this.user.setChoice(choice);
    }

    protected boolean isEliminated() {
        return this.user.isEliminated();
    }

    protected void setEliminated(boolean isEliminated) {
        this.user.setEliminated(isEliminated);
        this.user.setEliminated(isEliminated);
    }

    protected String getLastChoice() {
        return this.user.getLastChoice();
    }

    protected void setLastChoice(String lastChoice) {
        this.user.setLastChoice(lastChoice);
    }

    protected boolean isAway() {
        return this.user.isAway();
    }

    protected void setAway(boolean isAway) {
        this.user.setAway(isAway);
    }

    protected boolean isSpectator() {
        // Implicit spectator logic: not ready = spectator
        return !this.user.isReady();
    }

    /*
     * protected void setSpectator(boolean isSpectator) {
     * this.user.setSpectator(isSpectator);
     * }
     */

    @Override
    protected void onInitialized() {
        // once receiving the desired client name the object is ready
        onInitializationComplete.accept(this);
    }
}