pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// ── Sift design-slop auditor (composite build) ──────────────────────────────────────────────────
// Sift is a sibling repo (the Compose design-quality auditor). It is consumed as a *test-only*
// dependency without publishing, via a composite build: dependency substitution maps
// `style.sift:sift-compose` to Sift's `:sift-compose` project (Sift sets group = "style.sift").
//
// `siftDir` points at the Sift checkout, defaulting to the sibling clone used in local dev. CI
// checks Sift out next to this build and passes `-PsiftDir=sift-src`. The existence guard keeps a
// standalone Hawksnest checkout (no Sift alongside) building and unit-testing normally — the Sift
// audit source set is simply not wired in (see app/build.gradle.kts: `siftAvailable`).
val siftDir: String = gradle.startParameter.projectProperties["siftDir"] ?: "../../Sift"
if (rootDir.resolve(siftDir).resolve("settings.gradle.kts").exists()) {
    includeBuild(siftDir)
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Hawksnest"
include(":app")
