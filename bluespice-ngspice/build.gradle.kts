dependencies {
    api(project(":bluespice-core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(project(":bluespice-test-common"))
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}
