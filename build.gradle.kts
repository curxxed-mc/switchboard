plugins {
    java
    application
}

group = "curxxed.dev"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Compiles on whatever JDK runs Gradle (25 here) but targets the Java 21 API and bytecode.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

// sqlite-jdbc loads a native library; Java 22+ warns unless native access is granted explicitly.
val nativeAccess = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass = "curxxed.dev.switchboard.Switchboard"
    applicationDefaultJvmArgs = nativeAccess
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(nativeAccess)
}
