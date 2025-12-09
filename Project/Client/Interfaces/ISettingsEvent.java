package Project.Client.Interfaces;

public interface ISettingsEvent extends IClientEvents {
    void onReceiveSettings(String key, String value);
}
