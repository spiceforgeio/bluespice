plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

dependencies {
    testImplementation(project(":bluespice-test-common"))
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.spiceforgeio"
            artifactId = project.name
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("BlueSpice Core")
                description.set("Core API types and backend-agnostic interfaces for BlueSpice circuit simulation.")
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
