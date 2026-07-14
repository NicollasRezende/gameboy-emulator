plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":nes"))
}

application {
    mainClass.set("gb.cli.MainKt")
}

// Caminhos relativos passados em --args resolvem a partir da raiz do projeto.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

kotlin { jvmToolchain(21) }
