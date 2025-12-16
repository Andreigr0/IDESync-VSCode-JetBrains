import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("com.github.ben-manes.versions") version "0.51.0"
}

group = "com.vscode.jetbrainssync"
version = file("version.properties").readLines().first().substringAfter("=").trim()

repositories {
    mavenCentral()
    maven { url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies") }
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.jetbrains.rd:rd-core:2023.3.2")
    implementation("com.jetbrains.rd:rd-framework:2023.3.2")

    intellijPlatform {
        // Target IntelliJ Platform 2025.1 (251.*) – совместимо с Android Studio Narwhal 2025.1.x
        intellijIdeaCommunity("2025.1")
    }
}

sourceSets {
    main {
        kotlin {
            srcDirs("src/main/kotlin")
        }
        resources {
            srcDirs("src/main/resources")
        }
    }
}

intellijPlatform {
    // Disable tasks we don't need
    buildSearchableOptions = false
    instrumentCode = false

    projectName = project.name

    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            // Android Studio Narwhal 2025.1.x is based on 251.* platform builds
            sinceBuild = "251"
            untilBuild = null
        }
    }
}

tasks {
    register("syncVersionToVSCode") {
        group = "build"
        description = "Synchronize version from version.properties to VSCode extension package.json"
        
        doLast {
            val packageJsonFile = file("../vscode-extension/package.json")
            if (packageJsonFile.exists()) {
                val packageJson = packageJsonFile.readText()
                val updatedPackageJson = packageJson.replace(
                    """"version":\s*"[^"]*"""".toRegex(),
                    """"version": "${project.version}""""
                )
                packageJsonFile.writeText(updatedPackageJson)
                println("VSCode Extension version synchronized to: ${project.version}")
            }
        }
    }

    buildPlugin {
        dependsOn("syncVersionToVSCode")
        archiveBaseName.set("vscode-jetbrains-sync")
        archiveVersion.set(project.version.toString())
    }

    runIde {
        // Configure JVM arguments for running the plugin
        jvmArgs("-Xmx2g")
    }
    
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    
    prepareSandbox {
        doLast {
            val sandboxPluginsDir = intellijPlatform.sandboxContainer
                .get()
                .dir("plugins/${project.name}/lib")
                .asFile

            copy {
                from("${project.projectDir}/src/main/resources")
                into(sandboxPluginsDir)
                include("**/*")
            }
        }
    }
} 

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}