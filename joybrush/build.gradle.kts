plugins {
    kotlin("multiplatform") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
}

// Coordinates the app build uses to pull these modules in through includeBuild (JB-0.05):
// "cc.joycreator.joybrush:core".
allprojects {
    group = "cc.joycreator.joybrush"
    version = "0.1"
}
