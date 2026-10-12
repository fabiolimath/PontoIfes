// App Ponto IFES em Kotlin nativo (migração do app Python/Briefcase).
//   sigrh: cliente do SIGRH, Kotlin puro (JVM), testável sem o Android
//   app:   o app Android (telas, configurações, notificações, alarme, atalhos)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PontoIfes"
include(":sigrh", ":app")
