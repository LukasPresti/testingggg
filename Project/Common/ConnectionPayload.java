package Project.Common;

public class ConnectionPayload extends Payload {
    private String clientName;

    /**
     * @return the clientName
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * @param clientName the clientName to set
     */
    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    private boolean isAway;
    private boolean isSpectator;

    public boolean isAway() {
        return isAway;
    }

    public void setAway(boolean isAway) {
        this.isAway = isAway;
    }

    public boolean isSpectator() {
        return isSpectator;
    }

    public void setSpectator(boolean isSpectator) {
        this.isSpectator = isSpectator;
    }

    @Override
    public String toString() {
        return super.toString() +
                String.format(" ClientName: [%s]",
                        getClientName());
    }

}
