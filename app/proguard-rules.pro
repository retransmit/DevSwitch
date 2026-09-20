# Shizuku starts the user service in its own process and loads the class from this APK by
# name, so neither the service nor its AIDL interface may be renamed or stripped.
-keep class com.crazyapp.devtoggle.shizuku.ShellService { *; }
-keep class com.crazyapp.devtoggle.IShellService { *; }
-keep class com.crazyapp.devtoggle.IShellService$Stub { *; }
