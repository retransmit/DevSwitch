# Shizuku starts the user service in its own process and loads the class from this APK by
# name, so neither the service nor its AIDL interface may be renamed or stripped.
-keep class app.devswitch.shizuku.ShellService { *; }
-keep class app.devswitch.IShellService { *; }
-keep class app.devswitch.IShellService$Stub { *; }
