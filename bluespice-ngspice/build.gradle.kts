dependencies {
    api(project(":bluespice-core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(project(":bluespice-test-common"))
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
}

val nativesDir = file("src/main/resources/natives")
if (nativesDir.exists()) {
    sourceSets.main.get().resources.srcDir("src/main/resources")
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}
