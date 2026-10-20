# kotlinx.serialization: keep generated serializers for the wire protocol types.
-keepclassmembers @kotlinx.serialization.Serializable class app.handspan.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.handspan.core.**$$serializer { *; }
