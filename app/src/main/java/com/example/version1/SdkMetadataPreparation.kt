package com.example.version1

/** Warm public Kotlin metadata off-main. SDK permission checks and API invocations remain unchanged. */
internal object SdkMetadataPreparation {
    private val cache = BackgroundPreparationCache { type ->
        PerformanceMarks.measure("sdk.metadata.${type.simpleName}") {
            // The SDK's first invocation reads callable annotations, not just the
            // member list. Warm those public descriptors off-main as well.
            val visited=mutableSetOf<Class<*>>()
            fun warm(current:Class<*>) {
                if(!visited.add(current) || !current.name.startsWith("ai.pleos.playground.vehicle.")) return
                current.kotlin.members.forEach { it.annotations }
                current.interfaces.forEach(::warm)
                current.superclass?.let(::warm)
            }
            warm(type)
        }
    }

    fun forApis(vararg types: Class<*>): SdkApiPreparation =
        SdkApiPreparation(types.distinct().map(cache::prepare))
}
