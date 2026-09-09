plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
