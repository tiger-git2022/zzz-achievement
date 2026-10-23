plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only picks the Kotlin version it uses (the glass library needs 2.4).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

val localBuildRoot = file(System.getProperty("user.home"))
    .resolve(".gradle/project-builds/zzz-achievement")

layout.buildDirectory.set(localBuildRoot.resolve("root"))
subprojects {
    layout.buildDirectory.set(localBuildRoot.resolve(name))
}
