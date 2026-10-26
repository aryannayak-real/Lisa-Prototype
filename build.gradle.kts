plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "com.lisa"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
    testImplementation("io.ktor:ktor-client-mock:3.2.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.lisa.ai.MainKt")
}


tasks.test {
    useJUnitPlatform()
}

tasks.register("printRuntimeClasspath") {
    doLast {
        println(sourceSets.main.get().runtimeClasspath.asPath)
    }
}
