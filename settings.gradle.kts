rootProject.name = "microscenery"

include("core")
include("frontend")

val withZenSysConCon: String? by extra
if (withZenSysConCon?.toBoolean() == true) {
    include("zenSysConCon")
}

includeBuild("../scenery") {
    dependencySubstitution {
        substitute(module("com.github.scenerygraphics:scenery"))
            .using(project(":"))
    }
}