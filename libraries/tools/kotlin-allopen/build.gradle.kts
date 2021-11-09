import org.jetbrains.kotlin.pill.PillExtension.Variant

plugins {
    id("gradle-plugin-common-configuration")
    id("jps-compatible")
}

pill {
    variant = Variant.FULL
}

dependencies {
    commonCompileOnly(project(":kotlin-compiler-embeddable"))
    commonCompileOnly(project(":kotlin-allopen-compiler-plugin"))

    commonApi(project(":kotlin-gradle-plugin-model"))

    embedded(project(":kotlin-allopen-compiler-plugin")) {
        isTransitive = false
    }
}

gradlePlugin {
    (plugins) {
        register("kotlinAllopenPlugin") {
            id = "org.jetbrains.kotlin.plugin.allopen"
            implementationClass = "org.jetbrains.kotlin.allopen.gradle.AllOpenGradleSubplugin"
        }

        register("kotlinSpringPlugin") {
            id = "org.jetbrains.kotlin.plugin.spring"
            implementationClass = "org.jetbrains.kotlin.allopen.gradle.SpringGradleSubplugin"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinAllopenPlugin" {
            id = "org.jetbrains.kotlin.plugin.allopen"
            description = "Kotlin All Open compiler plugin"
            displayName = description
            tags = tags + "allopen"
        }

        "kotlinSpringPlugin" {
            id = "org.jetbrains.kotlin.plugin.spring"
            description = "Kotlin Spring compiler plugin"
            displayName = description
            tags = tags + "spring"
        }
    }
}
