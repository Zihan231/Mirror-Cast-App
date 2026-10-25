plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM on purpose: no Android dependency, so it is unit-testable on the host and usable
// from both Android apps. Android modules consume it as a normal project dependency.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.okhttp)
    api(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnit()
}
