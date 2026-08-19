pluginManagement {
    // Local composite: resolve dev.custompack.bundle from the sibling monorepo
    // without GitHub Packages credentials.
    includeBuild("../custompack")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "crafting-manager"
