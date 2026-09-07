android {
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:twitter:stub"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.annotation)
    compileOnly(libs.appcompat)
}

// JUnit for the pure time-limit engine (extensions/.../patches/timelimit). The
// Android pieces (stores, activities, UsageStats) are verified on-device.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
