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
    commonCompileOnly(project(":kotlin-sam-with-receiver-compiler-plugin"))

    commonApi(project(":kotlin-gradle-plugin-model"))

    embedded(project(":kotlin-sam-with-receiver-compiler-plugin")) { isTransitive = false }

    testImplementation(gradleApi())
    testImplementation("junit:junit:4.12")
}

gradlePlugin {
    (plugins) {
        register("kotlinSamWithReceiver") {
            id = "org.jetbrains.kotlin.plugin.sam.with.receiver"
            implementationClass = "org.jetbrains.kotlin.samWithReceiver.gradle.SamWithReceiverGradleSubplugin"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinSamWithReceiver" {
            id = "org.jetbrains.kotlin.plugin.sam.with.receiver"
            description = "Kotlin Sam with receiver compiler plugin"
            displayName = description
        }
    }
}
