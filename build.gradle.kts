import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
}

group = "dev.bluespice"
version = providers.gradleProperty("version").orElse("0.1.0-SNAPSHOT").get()

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        val tags = project.findProperty("tags")?.toString() ?: "unit"
        useJUnitPlatform {
            includeTags(tags)
        }
        if (tags == "intg") {
            forkEvery = 1
        }
        listOf("java.library.path", "jna.library.path", "bluespice.ngspice.library.path").forEach { property ->
            System.getProperty(property)?.let { systemProperty(property, it) }
        }
        testLogging {
            events("failed", "skipped")
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

configure(listOf(project(":bluespice-core"), project(":bluespice-ngspice"))) {
    apply(plugin = "jacoco")

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

tasks.register("jacocoTestReport") {
    dependsOn(":bluespice-core:jacocoTestReport", ":bluespice-ngspice:jacocoTestReport")
}
