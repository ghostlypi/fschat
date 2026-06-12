// Root build: shared configuration for all fschat modules.
//
// We compile with `--release 21` using whatever JDK runs Gradle (JDK 25 here).
// This produces Java 21-compatible bytecode without needing a separate JDK 21
// install or toolchain auto-provisioning.

subprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        // -serial: our exceptions intentionally omit serialVersionUID (never serialized).
        options.compilerArgs.add("-Xlint:all,-serial")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // sqlite-jdbc loads a native lib; silence the JDK 25 restricted-method warning.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
