
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.10"
    application
}

group = "me.jancasus"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven("https://maven.scijava.org/content/groups/public")
}



dependencies {
    // This should point to the most recent commit of scenery:jans-dirtier-branch
    // At the moment scenery:jans-dirtier-branch should be a merge of:
    // - jans-branch
    // - mitigate-apple-silicon-rendering-issue
    //implementation("com.github.scenerygraphics:scenery:1c65e6cf6d210bb08afda02df2cde9a38d50b28b")
    // local build version
    implementation("com.github.scenerygraphics:scenery:1.0.0-beta-3")


    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.zeromq:jeromq:0.5.2")
    implementation(project(":core"))
    val withZenSysConCon: String? by project
    if(withZenSysConCon?.toBoolean() == true) {
        implementation(project(":zenSysConCon"))
    }
    implementation(files("../core/manualLib/MMCoreJ.jar"))

    implementation("org.yaml:snakeyaml") {
        version { strictly("1.33") }
    }
    val scijavaParentPomVersion = project.properties["scijavaParentPOMVersion"]
    implementation(platform("org.scijava:pom-scijava:$scijavaParentPomVersion"))

    testImplementation("net.imagej:imagej")
    testImplementation("net.imagej:ij")
    testImplementation("net.imglib2:imglib2-ij")
    implementation("org.jfree:jfreechart:1.5.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.lwjgl:lwjgl-jawt:3.3.1")
    testImplementation(kotlin("test"))
}


tasks.test {
    jvmArgs = listOf("-Xmx28G")
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

application{
    mainClass = "microscenery.apps.RemoteSCAPEClientScene"
}

sourceSets {
    test {
        kotlin {
            val withZenSysConCon: String? by project
            if(withZenSysConCon?.toBoolean() == false || withZenSysConCon == null) {
                println("Excluding Zen-based tests")
                exclude("**/*Zen*.kt")
            }
        }
    }
}


tasks{
    // This registers gradle tasks for all example scenes
    sourceSets.test.get().allSource.files
        .filter { it.extension == "kt" }
        .map { it.path.substringAfter("kotlin${File.separatorChar}").replace(File.separatorChar, '.').substringBefore(".kt") }
        .filter { it.contains("microscenery.scenes.") && !it.contains("resources") }
        .forEach { className ->
            val exampleName = className.substringAfterLast(".")
            val exampleType = className.substringBeforeLast(".").substringAfter("microscenery.scenes.")

            register<JavaExec>(name = exampleName) {
                classpath = sourceSets.test.get().runtimeClasspath
                mainClass.set(className)
                group = "scenes.$exampleType"
                workingDir = workingDir.parentFile
                jvmArguments.add("-Dscenery.Renderer.Device=NVIDIA")

                val props = System.getProperties().filter { (k, _) -> k.toString().startsWith("scenery.") }

                val additionalArgs = System.getenv("SCENERY_JVM_ARGS")
                allJvmArgs = if (additionalArgs != null) {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") } + additionalArgs
                } else {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") }
                }
            }
        }
}
