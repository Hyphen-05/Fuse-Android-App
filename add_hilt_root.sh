sed -i '/plugins {/a \ \ \ \ alias(libs.plugins.dagger.hilt.android) apply false' build.gradle.kts
