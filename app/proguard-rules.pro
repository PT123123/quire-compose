# The bridge's exports are resolved by name through dlsym, so their names must
# survive everything between rustc and the APK.
-keepclasseswithmembernames class dev.quire.compose.bridge.Native {
    native <methods>;
}
