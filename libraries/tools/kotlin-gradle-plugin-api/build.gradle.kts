plugins {
    id("gradle-plugin-dependency-configuration")
    id("jps-compatible")
}

dependencies {
    "commonApi"(project(":native:kotlin-native-utils"))
    "commonApi"(project(":kotlin-project-model"))

    "commonCompileOnly"("com.android.tools.build:gradle:3.4.0")
}