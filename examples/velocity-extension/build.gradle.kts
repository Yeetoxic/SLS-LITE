import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "net.slimelabs.examples"
version = "1.0.0-SNAPSHOT"

val slsLiteVersion = providers.gradleProperty("slsLiteVersion").orElse("0.1.0-SNAPSHOT")

repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("net.slimelabs:sls-lite:${slsLiteVersion.get()}:api")
    compileOnly("com.velocitypowered:velocity-api:4.1.0-20260719.140524-3")
    compileOnly("com.google.inject:guice:7.0.0")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}

// The pinned Velocity 4 snapshot publishes Java 25 Gradle metadata. Compile on the same JDK as
// SLS-LITE while still emitting Java 21-compatible example bytecode through --release below.
configurations.compileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.add("-proc:none")
}
