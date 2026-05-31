plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
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

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val nativePlatforms = linkedMapOf(
    "linux-x86_64" to "libngspice.so",
    "linux-aarch64" to "libngspice.so",
    "windows-x86_64" to "ngspice.dll",
    "macos-x86_64" to "libngspice.dylib",
    "macos-aarch64" to "libngspice.dylib",
)
val nativeResourceRoot = layout.buildDirectory.dir("native-resources/natives")
val publishNativeArtifacts = providers.gradleProperty("publishNativeArtifacts")
    .map(String::toBoolean)
    .orElse(false)

val verifyNativeResources by tasks.registering {
    group = "verification"
    description = "Verifies all native libraries required for native artifact publication are staged."
    inputs.dir(nativeResourceRoot)
    doLast {
        val root = nativeResourceRoot.get().asFile
        val missing = nativePlatforms
            .map { (platform, fileName) -> platform to root.resolve("$platform/$fileName") }
            .filterNot { (_, file) -> file.isFile }
        check(missing.isEmpty()) {
            missing.joinToString(
                prefix = "Missing native libraries for publication: ",
                separator = ", ",
            ) { (platform, file) -> "$platform (${file.relativeTo(projectDir)})" }
        }
    }
}

val allJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles a fat JAR with BlueSpice ngspice classes, runtime dependencies, and native resources."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from(nativeResourceRoot) {
        into("natives")
    }
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.isFile }.map { zipTree(it) }
    })
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}

val nativeJars = nativePlatforms.map { (platform, _) ->
    tasks.register<Jar>("native${platform.replace("-", "_").replaceFirstChar { it.uppercase() }}Jar") {
        group = "build"
        description = "Assembles the $platform native classifier JAR when its binary is present."
        archiveClassifier.set(platform)
        val platformDir = nativeResourceRoot.map { it.dir(platform) }
        from(platformDir) {
            into("natives/$platform")
        }
    }
}

artifacts {
    if (publishNativeArtifacts.get()) {
        add("archives", allJar)
        nativeJars.forEach { add("archives", it) }
    }
}

if (publishNativeArtifacts.get()) {
    allJar.configure {
        dependsOn(verifyNativeResources)
    }
    nativeJars.forEach {
        it.configure {
            dependsOn(verifyNativeResources)
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.spiceforgeio"
            artifactId = project.name
            version = project.version.toString()
            from(components["java"])
            if (publishNativeArtifacts.get()) {
                artifact(allJar)
                nativeJars.forEach { artifact(it) }
            }
            pom {
                name.set("BlueSpice ngspice")
                description.set("ngspice-backed simulation engine and worker process implementation for BlueSpice.")
                url.set("https://github.com/spiceforgeio/bluespice")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("spiceforgeio")
                        name.set("spiceforgeio")
                        url.set("https://github.com/spiceforgeio")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/spiceforgeio/bluespice.git")
                    developerConnection.set("scm:git:ssh://github.com/spiceforgeio/bluespice.git")
                    url.set("https://github.com/spiceforgeio/bluespice")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "spiceforgeio/bluespice"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val key = System.getenv("MAVEN_GPG_PRIVATE_KEY")
    val pass = System.getenv("MAVEN_GPG_PASSPHRASE")
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications)
    }
}
