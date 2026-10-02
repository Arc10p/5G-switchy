plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.arc10p.fivegswitch.hiddenapistub"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
