package Project.Common;

public enum PayloadType {
       CLIENT_CONNECT, // client requesting to connect to server (passing of initialization data
                       // [name])
       CLIENT_ID, // server sending client id
       SYNC_CLIENT, // silent syncing of clients in room
       DISCONNECT, // distinct disconnect action
       ROOM_CREATE,
       ROOM_JOIN,
       ROOM_LEAVE,
       REVERSE,
       MESSAGE, // sender and message
       ROOM_LIST, // list of rooms
       READY, // client to trigger themselves as ready
       SYNC_READY, // quiet version of READY
       RESET_READY, // trigger to tell the client to reset their whole local list's ready status
       PHASE, // syncs current phase of session
       TURN, // example of taking a turn and syncing a turn action
       SYNC_TURN, // quiet version of TURN
       RESET_TURN, // trigger to tell client to reset their local list turn status
       TIME, // syncs current time of various timers
       POINTS, // syncs points data
       HOST, // syncs host data
       SETTINGS, // syncs game settings
       AWAY, // NEW: syncs away status
       SYNC_AWAY, // NEW: quiet sync of away status
}
