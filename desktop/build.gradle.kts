import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":innertube"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(compose.foundation)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.animation)
    implementation(libs.compose.icons.core)
    implementation(libs.materialKolor)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)

    implementation(libs.datastore.core)
    implementation(libs.coroutines.swing)

    implementation(libs.vlcj)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "miliMusic"
            packageVersion = "13.7.0"
            description = "miliMusic — YouTube Music Desktop Client"
            vendor = "angelvalentin-dev"
            licenseFile.set(rootProject.file("LICENSE"))

            modules(
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.sql",
                "java.xml",
                "jdk.accessibility",
                "jdk.crypto.ec",
                "jdk.httpserver",
                "jdk.unsupported",
            )

            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                iconFile.set(project.file("src/main/resources/icons/milimusic.ico"))
                menuGroup = "miliMusic"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "b8c4e1a2-7c9f-4f4e-9d1a-3b6e5c8a2d10"
            }
        }
    }
}
