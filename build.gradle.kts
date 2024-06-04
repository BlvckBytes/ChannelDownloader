import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.10"
}

group = "me.blvckbytes"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://maven.scijava.org/content/repositories/public/")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("me.blvckbytes:SpringHttpTesting:0.1")
    implementation("org.json:json:20231013")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.github.sealedtx:java-youtube-downloader:3.2.3")
    implementation("org.mp4parser:isoparser:1.9.56")
    implementation("org.mp4parser:muxer:1.9.56")
    implementation("org.slf4j:slf4j-api:2.1.0-alpha1")
    runtimeOnly("org.slf4j:slf4j-simple:2.1.0-alpha1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}