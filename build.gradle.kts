plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.nmcp.aggregation)
}

dependencies {
    nmcpAggregation(project(":core"))
    nmcpAggregation(project(":syncable-objects"))
    nmcpAggregation(project(":mock-infra"))
    nmcpAggregation(project(":mock-mode"))
    nmcpAggregation(project(":testing"))
    nmcpAggregation(project(":hilt"))
}

nmcpAggregation {
    centralPortal {
        username = findProperty("mavenCentralUsername") as String? ?: ""
        password = findProperty("mavenCentralPassword") as String? ?: ""
        publishingType = "AUTOMATIC"
    }
}
