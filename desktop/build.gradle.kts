plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":core"))
    implementation("net.java.jinput:jinput:2.0.10") // gamepad (best-effort, degrada se indisponível)
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
