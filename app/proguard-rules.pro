# kotlinx.serialization: keep the generated serializers for the tile config.
-keepclassmembers class dev.sal.glass.** {
    *** Companion;
}
-keepclasseswithmembers class dev.sal.glass.** {
    kotlinx.serialization.KSerializer serializer(...);
}
