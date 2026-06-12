plugins {
    application
}

dependencies {
    implementation(project(":fschat-protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.java.websocket)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bcrypt)
    implementation(libs.java.jwt)
    implementation(libs.picocli)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.fschat.server.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Stable release-asset name (no version suffix) for the install script.
tasks.named<org.gradle.api.tasks.bundling.Tar>("distTar") {
    archiveFileName.set("fschat-server.tar")
}
