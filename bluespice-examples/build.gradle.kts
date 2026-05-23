dependencies {
    implementation(project(":bluespice-core"))
    implementation(project(":bluespice-ngspice"))
    implementation(project(":bluespice-test-common"))

    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}

tasks.register<JavaExec>("runRcSmoke") {
    group = "verification"
    description = "Runs the rc-filter smoke circuit through the real ngspice worker path."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bluespice.examples.RcSmoke")
    listOf("java.library.path", "jna.library.path", "bluespice.ngspice.library.path").forEach { property ->
        System.getProperty(property)?.let { systemProperty(property, it) }
    }
}

tasks.register<JavaExec>("runGridExample") {
    group = "verification"
    description = "Runs the tick-based electrical grid example through the real ngspice worker path."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bluespice.examples.ElectricalGridExample")
    listOf("java.library.path", "jna.library.path", "bluespice.ngspice.library.path").forEach { property ->
        System.getProperty(property)?.let { systemProperty(property, it) }
    }
}
