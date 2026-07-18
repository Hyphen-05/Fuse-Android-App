sed -i '/alias(libs.plugins.google.devtools.ksp)/a \ \ alias(libs.plugins.dagger.hilt.android)' app/build.gradle.kts
sed -i '/dependencies {/a \ \ implementation(libs.hilt.android)\n\ \ ksp(libs.hilt.android.compiler)\n\ \ implementation(libs.androidx.hilt.navigation.compose)' app/build.gradle.kts
