import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates the GitHub Pages site: the static page in site/ plus a fresh schedule.json.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "ua.blackout.cherkasy.web.MainKt"
}

/** ./gradlew :web:site [-PpreviousUrl=https://…/schedule.json] → web/build/site */
tasks.register<JavaExec>("site") {
    group = "build"
    description = "Builds the GitHub Pages site with a freshly fetched schedule.json."
    val out = layout.buildDirectory.dir("site")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    inputs.dir("site")
    outputs.dir(out)
    outputs.upToDateWhen { false } // always fetch fresh data
    doFirst {
        val dir = out.get().asFile
        dir.deleteRecursively()
        project.copy { from("site"); into(dir) }
    }
    argumentProviders += CommandLineArgumentProvider {
        listOfNotNull(out.get().asFile.absolutePath, providers.gradleProperty("previousUrl").orNull)
    }
}
