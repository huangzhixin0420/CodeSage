plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.codesage"
version = "2026.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.2")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get().filter { it.name.contains("okhttp") || it.name.contains("okio") || it.name.contains("snakeyaml") || it.name.contains("kotlinx-serialization") || it.name.contains("sqlite") || it.name.contains("pdfbox") || it.name.contains("fontbox") || it.name.contains("commons-logging") }
                .map { if (it.isDirectory) it else zipTree(it) }
        })
    }

    test {
        useJUnitPlatform()
    }

    // buildSearchableOptions 在 headless 环境下会因 IntelliJ 平台 2026.1.2 的 TraverseUIStarter locale 校验失败
    // 这是平台已知问题（参见 IDEA-332952），JVM 参数无法修复。
    // 该任务仅生成 IDE 设置页搜索索引，禁用不影响插件核心功能。
    named<JavaExec>("buildSearchableOptions") {
        enabled = false
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
