import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.library") version "8.6.1" apply false
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
    id("com.vanniktech.maven.publish.base") version "0.35.0" apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

tasks.register("stageRelease") {
    description = "Stage the release publication locally; never uploads to Maven Central."
    dependsOn(":preference-debug-bridge:publishMavenPublicationToValidationRepository")
}

project(":preference-debug-bridge") {
    pluginManager.withPlugin("com.vanniktech.maven.publish.base") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(
                AndroidSingleVariantLibrary(
                    variant = "release",
                    sourcesJar = true,
                    publishJavadocJar = false,
                ),
            )
            coordinates(
                providers.gradleProperty("GROUP").get(),
                providers.gradleProperty("POM_ARTIFACT_ID").get(),
                providers.gradleProperty("VERSION_NAME").get(),
            )
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            pom {
                name.set("Mobile Build Preference Debug Bridge")
                description.set(
                    "A debug-only Android bridge for inspecting and editing explicitly registered SharedPreferences through Mobile Build.",
                )
                url.set("https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("chinmaydash007")
                        name.set("chinmaydash007")
                        url.set("https://github.com/chinmaydash007")
                    }
                }
                scm {
                    url.set("https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge")
                    connection.set(
                        "scm:git:https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge.git",
                    )
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/chinmaydash007/mobilebuild-preference-debug-bridge.git",
                    )
                }
            }
        }

        val apiDocsJar =
            tasks.register<Jar>("apiDocsJar") {
                dependsOn("dokkaHtml")
                archiveClassifier.set("javadoc")
                from(layout.buildDirectory.dir("dokka/html"))
                from(rootProject.file("README.md"))
                from(rootProject.file("LICENSE"))
                from(rootProject.file("CONTRIBUTING.md"))
                from(rootProject.file("docs")) { into("docs") }
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }

        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "Validation"
                url =
                    rootProject.layout.buildDirectory
                        .dir("validation-repository")
                        .get()
                        .asFile
                        .toURI()
            }
            publications.withType<MavenPublication>().configureEach { artifact(apiDocsJar) }
        }

        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            if (name == "sourceReleaseJar" || name.contains("sources", ignoreCase = true)) {
                from(rootProject.file("README.md"))
                from(rootProject.file("LICENSE"))
                from(rootProject.file("CONTRIBUTING.md"))
                from(rootProject.file("docs")) { into("docs") }
            }
        }
    }
}
