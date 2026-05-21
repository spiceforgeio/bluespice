plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":bluespice-core"))
    jmhImplementation(project(":bluespice-ngspice"))
    jmhImplementation(project(":bluespice-test-common"))
    jmhImplementation(libs.jna)
}

jmh {
    includes.set(listOf(project.findProperty("jmhInclude")?.toString() ?: ".*BindingOverheadBenchmark.*"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    jvmArgs.set(listOfNotNull(
        System.getProperty("jna.library.path")?.let { "-Djna.library.path=$it" },
        System.getProperty("bluespice.ngspice.library.path")?.let { "-Dbluespice.ngspice.library.path=$it" },
    ))
}
