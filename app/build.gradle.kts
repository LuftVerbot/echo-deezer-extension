import com.android.build.gradle.AppExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply<EchoExtensionPlugin>()
configure<EchoExtension> {
    versionCode = 1
    versionName = "1.0.0"
    extensionClass = "DeezerExtension"
    id = "deezer"
    name = "Deezer"
    description = "Deezer Extension for Echo."
    author = "Luftnos"
    iconUrl = "https://e-cdn-files.dzcdn.net/cache/images/common/favicon/favicon-240x240.bb3a6a29ad16a77f10cb.png"
}

dependencies {
    implementation(project(":ext"))
    val libVersion: String by project
    compileOnly("com.github.brahmkshatriya:echo:$libVersion")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.deezer"
        minSdk = 24
        targetSdk = 34
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
open class EchoExtension {
    var extensionClass: String? = null
    var id: String? = null
    var name: String? = null
    var description: String? = null
    var author: String? = null
    var iconUrl: String? = null
    var versionCode: Int? = null
    var versionName: String? = null
}

abstract class EchoExtensionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val echoExtension = project.extensions.create("echoExtension", EchoExtension::class.java)
        project.afterEvaluate {
            project.extensions.configure<AppExtension>("android") {
                defaultConfig.apply {
                    with(echoExtension) {
                        resValue("string", "id", id!!)
                        resValue("string", "name", name!!)
                        resValue("string", "app_name", "Echo : $name Extension")
                        val extensionClass = extensionClass!!
                        resValue("string", "class_path", "$namespace.$extensionClass")
                        resValue("string", "version", versionName!!)
                        resValue("string", "description", description!!)
                        resValue("string", "author", author!!)
                        iconUrl?.let { resValue("string", "icon_url", it) }
                    }
                }
            }
        }
    }
}