import java.security.MessageDigest
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}

val publicationGroup = providers.gradleProperty("GROUP").get()
val publicationVersion = providers.gradleProperty("VERSION_NAME").get()
val publicationArtifact = providers.gradleProperty("POM_ARTIFACT_ID").get()

group = publicationGroup
version = publicationVersion

android {
    namespace = "io.github.chinmaydash007.mobilebuild.preferences"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-Xjvm-default=all"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.12.0")
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("dokkaHtml"))
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml").map { it.outputs.files })
}

val centralStagingDirectory = layout.buildDirectory.dir("central-staging")

publishing {
    repositories {
        maven {
            name = "centralStaging"
            url = uri(centralStagingDirectory)
        }
    }
}

afterEvaluate {
    val releasePublication = publishing.publications.create<MavenPublication>("release") {
        groupId = publicationGroup
        artifactId = publicationArtifact
        version = publicationVersion
        from(components["release"])
        artifact(dokkaJavadocJar)

        pom {
            name.set("Mobile Build Preference Debug Bridge")
            description.set(
                "A debug-only Android bridge for inspecting and editing explicitly registered SharedPreferences through Mobile Build."
            )
            url.set("https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge")
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
                connection.set("scm:git:git://github.com/chinmaydash007/mobilebuild-preference-debug-bridge.git")
                developerConnection.set("scm:git:ssh://github.com/chinmaydash007/mobilebuild-preference-debug-bridge.git")
                url.set("https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge")
            }
        }
    }

    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (!signingKey.isNullOrBlank()) {
        signing {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(releasePublication)
        }
    }
}

val centralBundleRoot = layout.buildDirectory.dir("central-bundle-root")
val groupPath = publicationGroup.replace('.', '/')
val centralVersionPath = "$groupPath/$publicationArtifact/$publicationVersion"

val prepareCentralBundle by tasks.registering {
    dependsOn("publishReleasePublicationToCentralStagingRepository")
    doLast {
        check(!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
            "Central publication requires -PsigningInMemoryKey and -PsigningInMemoryKeyPassword."
        }

        val source = centralStagingDirectory.get().dir(centralVersionPath).asFile
        check(source.isDirectory) { "Published staging directory is missing: $source" }

        val root = centralBundleRoot.get().asFile
        delete(root)
        val destination = root.resolve(centralVersionPath)
        copy {
            from(source)
            into(destination)
        }

        val primaryFiles = destination.listFiles().orEmpty().filter {
            it.isFile && !it.name.endsWith(".md5") && !it.name.endsWith(".sha1") &&
                !it.name.endsWith(".sha256") && !it.name.endsWith(".sha512")
        }
        val expectedSuffixes = listOf(".aar", ".pom", "-sources.jar", "-javadoc.jar")
        expectedSuffixes.forEach { suffix ->
            check(primaryFiles.any { it.name.endsWith(suffix) }) { "Central artifact is missing *$suffix" }
        }
        primaryFiles.filterNot { it.name.endsWith(".asc") }.forEach { artifact ->
            check(destination.resolve("${artifact.name}.asc").isFile) {
                "Central artifact is unsigned: ${artifact.name}"
            }
        }

        fun checksum(file: java.io.File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        primaryFiles.forEach { artifact ->
            mapOf(
                "md5" to "MD5",
                "sha1" to "SHA-1",
                "sha256" to "SHA-256",
                "sha512" to "SHA-512"
            ).forEach { (extension, algorithm) ->
                destination.resolve("${artifact.name}.$extension")
                    .writeText(checksum(artifact, algorithm))
            }
        }
    }
}

tasks.register<Zip>("centralBundle") {
    dependsOn(prepareCentralBundle)
    from(centralBundleRoot)
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    archiveFileName.set("$publicationArtifact-$publicationVersion.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
