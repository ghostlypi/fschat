plugins {
    application
}

dependencies {
    implementation(project(":fschat-protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.sqlite.jdbc)
    implementation(libs.picocli)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Integration tests drive a real server in-process.
    testImplementation(project(":fschat-server"))
    testImplementation(libs.java.websocket) // for FschatWsServer's inherited start/stop
}

application {
    mainClass.set("dev.fschat.daemon.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Bundle the Vim plugin inside the daemon distribution so one tarball has everything.
distributions {
    named("main") {
        contents {
            from(rootProject.file("vim")) { into("vim") }
        }
    }
}

// Stable release-asset name (no version suffix) for the install script.
tasks.named<org.gradle.api.tasks.bundling.Tar>("distTar") {
    archiveFileName.set("fschat-daemon.tar")
}
