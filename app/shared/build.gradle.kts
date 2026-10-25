plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
        }
        // Tệp test chạy trên iOS Simulator (`:shared:iosSimulatorArm64Test`) dùng Room + NativeSQLiteDriver
        // nên phải liên kết sqlite3 của hệ điều hành, giống framework của composeApp. SQLite của Apple không có
        // sqlite3_load_extension (không bao giờ được gọi), nên cho phép ký hiệu đó chưa được giải quyết.
        iosTarget.binaries.getTest("DEBUG").linkerOpts("-lsqlite3", "-Wl,-U,_sqlite3_load_extension")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            // Ktor HTTP client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // Room KMP Local Cache
            implementation(libs.room.runtime)

            // Supabase (Auth + Realtime only)
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.realtime)

            // Koin DI
            implementation(libs.koin.core)
        }

        commonTest.dependencies {
            // Chay bang: ./gradlew :shared:allTests
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Bao chuong moi: WorkManager chay kiem tra khi ung dung khong mo,
            // NotificationCompat de hien thong bao dung tren moi doi Android.
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.core.ktx)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.truyencloud.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosX64", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
