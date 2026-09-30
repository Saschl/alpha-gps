plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sasch.cameragps.heif"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                targets += listOf("alpha_heif", "heif", "de265")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.exifinterface)
}
