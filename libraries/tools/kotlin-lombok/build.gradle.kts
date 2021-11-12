description = "Kotlin lombok compiler plugin"

plugins {
    id("gradle-plugin-common-configuration")
    id("jps-compatible")
}

dependencies {
    commonApi(project(":kotlin-gradle-plugin-model"))

    embedded(project(":plugins:lombok:lombok-compiler-plugin")) { isTransitive = false }
}

projectTest(parallel = true)

gradlePlugin {
    (plugins) {
        register("kotlinLombokPlugin") {
            id = "org.jetbrains.kotlin.plugin.lombok"
            implementationClass = "org.jetbrains.kotlin.lombok.gradle.LombokSubplugin"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinLombokPlugin" {
            id = "org.jetbrains.kotlin.plugin.lombok"
            description = "Kotlin Lombok plugin"
            displayName = description
        }
    }
}
