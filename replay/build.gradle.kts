plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    // The synthetic subject is simulation scaffolding shared with core's own tests.
    implementation(testFixtures(project(":core")))
}

application { mainClass.set("health.hydration.replay.MainKt") }
