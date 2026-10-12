android {
    namespace = "app.morphe.extension.instagram"

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:instagram:stub"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.annotation)
    compileOnly(libs.appcompat)
}

// JUnit for the pure Postbox logic (extensions/.../patches/postbox). Vantage fork only.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
