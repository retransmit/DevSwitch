package app.devswitch;

// Implemented by ShellService, which Shizuku runs with the shell uid and shell SELinux context.
// The wireless-debugging methods must run here, not in the app: only this context can look up the
// "adb" system service and holds MANAGE_DEBUGGING.
interface IShellService {
    // Transaction code reserved by the Shizuku server for tearing the service down.
    void destroy() = 16777114;

    // Runs the command (argv form), returns combined stdout/stderr, throws on a non-zero exit.
    String execute(in String[] command) = 1;

    // The device's wireless-debugging identity (persist.adb.wifi.guid), or "" if unset.
    String deviceGuid() = 2;

    // Starts a pairing session advertised as serviceName, secured by password.
    void enablePairing(String serviceName, String password) = 3;

    void disablePairing() = 4;

    // The wireless-debugging connect port, or 0 when wireless debugging is off.
    int wirelessPort() = 5;

    // One string per paired computer, fields joined by U+0001: fingerprint, host, "true"/"false".
    String[] pairedDevices() = 6;

    void unpairDevice(String fingerprint) = 7;
}
