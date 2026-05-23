plugins {
    `maven-publish`
}

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

val allJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles a fat JAR with BlueSpice ngspice classes, runtime dependencies, and native resources."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.isFile }.map { zipTree(it) }
    })
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}

val nativePlatforms = listOf("linux-x86_64", "linux-aarch64", "windows-x86_64", "macos-x86_64", "macos-aarch64")
val nativeJars = nativePlatforms.map { platform ->
    tasks.register<Jar>("native${platform.replace("-", "_").replaceFirstChar { it.uppercase() }}Jar") {
        group = "build"
        description = "Assembles the $platform native classifier JAR when its binary is present."
        archiveClassifier.set("natives-$platform")
        val platformDir = layout.projectDirectory.dir("src/main/resources/natives/$platform")
        from(platformDir) {
            into("natives/$platform")
        }
    }
}

artifacts {
    add("archives", allJar)
    nativeJars.forEach { add("archives", it) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.bluespice"
            artifactId = project.name
            version = project.version.toString()
            from(components["java"])
            artifact(allJar)
            nativeJars.forEach { artifact(it) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "raymond/bluespice"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
