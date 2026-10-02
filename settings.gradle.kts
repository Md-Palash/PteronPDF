pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF is NOT on Maven Central / Google: Artifex hosts it themselves.
        maven {
            url = uri("https://maven.ghostscript.com/")
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}
rootProject.name = "PteronPDF"
include(":app")
