# Partial packageScope optimization moved CollectionsKt__MutableCollectionsJVMKt
# into the default package while its package-private superclass remained in
# kotlin.collections. Actual nondebuggable launch then failed with IllegalAccessError
# (iteration738). Preserve Kotlin package boundaries, retaining shrinking, method
# optimization and class/member name obfuscation inside those packages.
-keeppackagenames kotlin.**

# kotlin-reflect reads these built-in annotation defaults through Java reflection
# while warming the Pleos gear API. Actual optimized QA failed with
# IncompleteAnnotationException at Proxy.replaceWith (iteration752). Preserve this
# small annotation contract including default nested annotation and enum values.
-keep @interface kotlin.Deprecated { *; }
-keep @interface kotlin.ReplaceWith { *; }
-keep enum kotlin.DeprecationLevel { *; }
