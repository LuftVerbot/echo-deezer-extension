plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 34

    defaultConfig {
        val extensionClass = "DeezerExtension"
        val id = "deezer"
        val name = "Deezer"
        val version = "1.0.0"
        val description = "Deezer Extension for Echo."
        val author = "luft"
        val iconUrl = "https://e-cdn-files.dzcdn.net/cache/images/common/favicon/favicon-240x240.bb3a6a29ad16a77f10cb.png"

        applicationId = "dev.brahmkshatriya.echo.extension.deezer"
        minSdk = 24
        targetSdk = 34

        versionCode = 1
        versionName = version

        resValue("string", "app_name", "Echo : $name Extension")
        resValue("string", "class_path", "$namespace.$extensionClass")
        resValue("string", "name", name)
        resValue("string", "id", id)
        resValue("string", "version", version)
        resValue("string", "description", description)
        resValue("string", "author", author)
        resValue("string", "icon_url", iconUrl)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests {
            this.isReturnDefaultValues = true
        }
    }
}



dependencies {
    val libVersion = "728a3edac8"
    compileOnly("com.github.brahmkshatriya:echo:$libVersion")
    implementation("com.github.yvasyliev:deezer-api:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1-Beta")
    testImplementation("androidx.paging:paging-runtime-ktx:3.2.1")
    testImplementation("com.github.brahmkshatriya:echo:8f951e48af")
}