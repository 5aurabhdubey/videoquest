buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Add the google-services plugin classpath
        classpath("com.google.gms:google-services:4.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}



tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}