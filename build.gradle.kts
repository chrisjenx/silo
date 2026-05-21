plugins {
    base
}

allprojects {
    group = "com.chrisjenx.silo"
    version = providers.gradleProperty("silo.version").getOrElse("0.1.0-SNAPSHOT")
}

val hooksDir = rootProject.layout.projectDirectory.dir(".githooks")

val markHooksExecutable = tasks.register("markGitHooksExecutable") {
    group = "git hooks"
    description = "Ensures every file under .githooks/ is executable."
    inputs.dir(hooksDir)
    doLast {
        hooksDir.asFile.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.forEach { it.setExecutable(true) }
    }
}

tasks.register<Exec>("installGitHooks") {
    group = "git hooks"
    description = "Points git core.hooksPath at .githooks/ and makes hook scripts executable."
    dependsOn(markHooksExecutable)
    workingDir = rootProject.projectDir
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast {
        logger.lifecycle("Configured core.hooksPath -> .githooks (${hooksDir.asFile.list()?.size ?: 0} entries).")
    }
}

tasks.named("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
