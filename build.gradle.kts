plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

val localBuildRoot = file(System.getProperty("user.home"))
    .resolve(".gradle/project-builds/zzz-achievement")

layout.buildDirectory.set(localBuildRoot.resolve("root"))
subprojects {
    layout.buildDirectory.set(localBuildRoot.resolve(name))
}
