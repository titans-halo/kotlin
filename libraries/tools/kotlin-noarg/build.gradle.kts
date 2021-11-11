import org.jetbrains.kotlin.pill.PillExtension.Variant.FULL

plugins {
    id("gradle-plugin-common-configuration")
    id("jps-compatible")
}

pill {
    variant = FULL
}

dependencies {
    commonCompileOnly(project(":compiler"))
    commonCompileOnly(project(":kotlin-noarg-compiler-plugin"))

    commonApi(project(":kotlin-gradle-plugin-model"))

    embedded(project(":kotlin-noarg-compiler-plugin")) { isTransitive = false }

    testImplementation(gradleApi())
    testImplementation("junit:junit:4.12")
}

gradlePlugin {
    (plugins) {
        register("kotlinNoargPlugin") {
            id = "org.jetbrains.kotlin.plugin.noarg"
            implementationClass = "org.jetbrains.kotlin.noarg.gradle.NoArgGradleSubplugin"
        }
        register("kotlinJpaPlugin") {
            id = "org.jetbrains.kotlin.plugin.jpa"
            implementationClass = "org.jetbrains.kotlin.noarg.gradle.KotlinJpaSubplugin"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinNoargPlugin" {
            id = "org.jetbrains.kotlin.plugin.noarg"
            description = "Kotlin No Arg compiler plugin"
            displayName = description
        }
        "kotlinJpaPlugin" {
            id = "org.jetbrains.kotlin.plugin.jpa"
            description = "Kotlin JPA compiler plugin"
            displayName = description
        }
    }
}
