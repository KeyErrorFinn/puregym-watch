plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.finnl.gymaccess.protocol"; compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation("com.google.zxing:core:3.5.3")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517")
}
