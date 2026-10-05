plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP's built-in Kotlin compiles with whatever Kotlin Gradle plugin is on the classpath
    // (2.2.10 by default). Pinning it here keeps the compiler and the Compose compiler plugin
    // on the same version.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
