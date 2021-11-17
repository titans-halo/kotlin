import org.jetbrains.kotlin.pill.PillExtension

plugins {
    id("gradle-plugin-common-configuration")
    id("jps-compatible")
}

apply(from = "functionalTest.gradle.kts")
val functionalTestImplementation by configurations

repositories {
    google()
    maven("https://plugins.gradle.org/m2/")
}

pill {
    variant = PillExtension.Variant.FULL
}

dependencies {
    commonCompileOnly(gradleKotlinDsl())
    commonCompileOnly(project(":compiler"))
    commonCompileOnly(project(":compiler:incremental-compilation-impl"))
    commonCompileOnly(project(":daemon-common"))
    commonCompileOnly(project(":native:kotlin-native-utils"))
    commonCompileOnly(project(":kotlin-reflect-api"))
    commonCompileOnly(project(":kotlin-reflect"))
    commonCompileOnly(project(":kotlin-android-extensions"))
    commonCompileOnly(project(":kotlin-build-common"))
    commonCompileOnly(project(":kotlin-compiler-runner"))
    commonCompileOnly(project(":kotlin-annotation-processing"))
    commonCompileOnly(project(":kotlin-annotation-processing-gradle"))
    commonCompileOnly(project(":kotlin-scripting-compiler"))
    commonCompileOnly(project(":kotlin-gradle-statistics"))
    commonCompileOnly(project(":kotlin-gradle-build-metrics"))
    commonCompileOnly(intellijCoreDep()) { includeJars("intellij-core") }
    commonCompileOnly(intellijDep()) { includeJars("asm-all", "gson", "guava", "serviceMessages", rootProject = rootProject) }
    commonCompileOnly("com.gradle:gradle-enterprise-gradle-plugin:3.6.3")
    commonCompileOnly("com.android.tools.build:gradle:3.4.0")
    commonCompileOnly("com.android.tools.build:gradle-api:3.4.0")
    commonCompileOnly("com.android.tools.build:builder:3.4.0")
    commonCompileOnly("com.android.tools.build:builder-model:3.4.0")

    commonApi(project(":kotlin-gradle-plugin-model"))

    commonImplementation(project(":kotlin-util-klib"))
    commonImplementation(project(":native:kotlin-klib-commonizer-api"))
    commonImplementation(project(":kotlin-tooling-metadata"))
    commonImplementation(project(":kotlin-project-model"))
    commonImplementation("com.google.code.gson:gson:${rootProject.extra["versions.jar.gson"]}")
    commonImplementation("com.google.guava:guava:${rootProject.extra["versions.jar.guava"]}")
    commonImplementation("de.undercouch:gradle-download-task:4.1.1")
    commonImplementation("com.github.gundy:semver4j:0.16.4:nodeps") {
        exclude(group = "*")
    }

    commonRuntimeOnly(project(":kotlin-compiler-embeddable"))
    commonRuntimeOnly(project(":kotlin-annotation-processing-gradle"))
    commonRuntimeOnly(project(":kotlin-android-extensions"))
    commonRuntimeOnly(project(":kotlin-compiler-runner"))
    commonRuntimeOnly(project(":kotlin-scripting-compiler-embeddable"))
    commonRuntimeOnly(project(":kotlin-scripting-compiler-impl-embeddable"))

    embedded(project(":kotlin-gradle-statistics")) { isTransitive = false }
    embedded(project(":kotlin-gradle-build-metrics")) { isTransitive = false }
    embedded(intellijDep()) {
        isTransitive = false
        includeJars("asm-all", "gson", "guava", "serviceMessages", "util", rootProject = rootProject)
    }

    functionalTestImplementation("com.android.tools.build:gradle:4.0.1") {
        because("Functional tests are using APIs from Android. Latest Version is used to avoid NoClassDefFoundError")
    }
    functionalTestImplementation(gradleKotlinDsl())

    testCompileOnly(project(":compiler"))
    testCompileOnly(project(":kotlin-reflect-api"))
    testCompileOnly(project(":kotlin-annotation-processing"))
    testCompileOnly(project(":kotlin-annotation-processing-gradle"))

    testImplementation(intellijDep()) { includeJars("junit", "serviceMessages", rootProject = rootProject) }
    testImplementation(projectTests(":kotlin-build-common"))
    testImplementation(project(":kotlin-android-extensions"))
    testImplementation(project(":kotlin-compiler-runner"))
    testImplementation(project(":kotlin-test::kotlin-test-junit"))
    testImplementation("junit:junit:4.12")
    testImplementation(project(":kotlin-gradle-statistics"))
}

if (kotlinBuildProperties.isInJpsBuildIdeaSync) {
    configurations.api.get().exclude("com.android.tools.external.com-intellij", "intellij-core")
}

tasks {
    named<ProcessResources>("processCommonResources") {
        val propertiesToExpand = mapOf(
            "projectVersion" to project.version,
            "kotlinNativeVersion" to project.kotlinNativeVersion
        )
        for ((name, value) in propertiesToExpand) {
            inputs.property(name, value)
        }
        filesMatching("project.properties") {
            expand(propertiesToExpand)
        }
    }

    withType<ValidatePlugins>().configureEach {
        failOnWarning.set(true)
        enableStricterValidation.set(true)
    }

    named("install") {
        dependsOn(named("validatePlugins"))
    }

    withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
        dokkaSourceSets.configureEach {
            includes.from("Module.md")
        }
    }
}

projectTest {
    dependsOn(tasks.named("validatePlugins"))
    workingDir = rootDir
}

gradlePlugin {
    (plugins) {
        register("kotlinJvmPlugin") {
            id = "org.jetbrains.kotlin.jvm"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper"
        }
        register("kotlinJsPlugin") {
            id = "org.jetbrains.kotlin.js"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.KotlinJsPluginWrapper"
        }
        register("kotlinMultiplatformPlugin") {
            id = "org.jetbrains.kotlin.multiplatform"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper"
        }
        register("kotlinAndroidPlugin") {
            id = "org.jetbrains.kotlin.android"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper"
        }
        register("kotlinAndroidExtensionsPlugin") {
            id = "org.jetbrains.kotlin.android.extensions"
            implementationClass = "org.jetbrains.kotlin.gradle.internal.AndroidExtensionsSubpluginIndicator"
        }
        register("kotlinParcelizePlugin") {
            id = "org.jetbrains.kotlin.plugin.parcelize"
            implementationClass = "org.jetbrains.kotlin.gradle.internal.ParcelizeSubplugin"
        }
        register("kotlinKaptPlugin") {
            id = "org.jetbrains.kotlin.kapt"
            implementationClass = "org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin"
        }
        register("kotlinScriptingPlugin") {
            id = "org.jetbrains.kotlin.plugin.scripting"
            implementationClass = "org.jetbrains.kotlin.gradle.scripting.internal.ScriptingGradleSubplugin"
        }
        register("kotlinNativeCocoapodsPlugin") {
            id = "org.jetbrains.kotlin.native.cocoapods"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.cocoapods.KotlinCocoapodsPlugin"
        }
        register("kotlinMultiplatformPluginPm20") {
            id = "org.jetbrains.kotlin.multiplatform.pm20"
            implementationClass = "org.jetbrains.kotlin.gradle.plugin.KotlinPm20PluginWrapper"
        }
    }
}

pluginBundle {
    (plugins) {
        "kotlinJvmPlugin" {
            id = "org.jetbrains.kotlin.jvm"
            description = "Kotlin JVM plugin"
            displayName = description
        }
        "kotlinJsPlugin" {
            id = "org.jetbrains.kotlin.js"
            description = "Kotlin JS plugin"
            displayName = description
        }
        "kotlinMultiplatformPlugin" {
            id = "org.jetbrains.kotlin.multiplatform"
            description = "Kotlin Multiplatform plugin"
            displayName = description
        }
        "kotlinAndroidPlugin" {
            id = "org.jetbrains.kotlin.android"
            description = "Kotlin Android plugin"
            displayName = description
        }
        "kotlinAndroidExtensionsPlugin" {
            id = "org.jetbrains.kotlin.android.extensions"
            description = "Kotlin Android Extensions plugin"
            displayName = description
        }
        "kotlinParcelizePlugin" {
            id = "org.jetbrains.kotlin.plugin.parcelize"
            description = "Kotlin Parcelize plugin"
            displayName = description
        }
        "kotlinKaptPlugin" {
            id = "org.jetbrains.kotlin.kapt"
            description = "Kotlin Kapt plugin"
            displayName = description
        }
        "kotlinScriptingPlugin" {
            id = "org.jetbrains.kotlin.plugin.scripting"
            description = "Gradle plugin for kotlin scripting"
            displayName = description
        }
        "kotlinNativeCocoapodsPlugin" {
            id = "org.jetbrains.kotlin.native.cocoapods"
            description = "Kotlin Native plugin for CocoaPods integration"
            displayName = description
        }
        "kotlinMultiplatformPluginPm20" {
            id = "org.jetbrains.kotlin.multiplatform.pm20"
            description = "Kotlin Multiplatform plugin with PM2.0"
            displayName = description
        }
    }
}