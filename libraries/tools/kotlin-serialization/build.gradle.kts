import org.jetbrains.kotlin.pill.PillExtension.Variant.FULL

plugins {
    id("gradle-plugin-common-configuration")
    id("jps-compatible")
}

pill {
    variant = FULL
}

dependencies {
    commonCompileOnly(project(":kotlin-gradle-plugin")) {
        capabilities {
            requireCapability("org.jetbrains.kotlin:kotlin-gradle-plugin-common")
        }
    }
    commonCompileOnly(project(":kotlin-compiler-embeddable"))

    embedded(project(":kotlinx-serialization-compiler-plugin")) { isTransitive = false }
}

gradlePlugin {
    (plugins) {
        register("kotlinSerialization") {
            id = "org.jetbrains.kotlin.plugin.serialization"
            implementationClass = "org.jetbrains.kotlinx.serialization.gradle.SerializationGradleSubplugin"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinSerialization" {
            id = "org.jetbrains.kotlin.plugin.serialization"
            description = "Kotlin compiler plugin for kotlinx.serialization library"
            displayName = description
        }
    }
}
