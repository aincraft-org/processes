plugins {
    java
    // Applied without version: resolved via pluginManagement includeBuild("../custompack")
    id("dev.custompack.bundle")
}

group = "dev.craftingmanager"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
                .filter { it.name.contains("sqlite-jdbc") }
                .map { zipTree(it) }
    })
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
}
