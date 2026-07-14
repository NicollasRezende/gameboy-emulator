plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":nes"))
    implementation(project(":snes"))
}

application {
    mainClass.set("gb.desktop.MainKt")
}

// Caminhos relativos passados em --args resolvem a partir da raiz do projeto.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

kotlin { jvmToolchain(21) }
