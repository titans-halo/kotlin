plugins {
    kotlin("jvm")
    id("jps-compatible")
}

publish()
configureKotlinCompileTasksGradleCompatibility()

dependencies {
    compileOnly(kotlinStdlib())
}
