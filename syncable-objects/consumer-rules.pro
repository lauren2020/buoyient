# syncable-objects consumer ProGuard rules
# These rules are bundled into the AAR and applied automatically by consumers.

# Keep kotlinx.serialization-generated serializers — required for SyncCodec
-keepclassmembers class * implements com.elvdev.buoyient.SyncableObject {
    <fields>;
}

# Keep @Serializable companion objects and their serializer() methods
-keepclassmembers class **$$serializer {
    *** INSTANCE;
    *** childSerializers(...);
    *** serialize(...);
    *** deserialize(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep SyncableObjectService subclasses — instantiated reflectively by the sync engine
-keep class * extends com.elvdev.buoyient.SyncableObjectService { *; }

# Keep sealed class hierarchies used for pattern matching
-keep class com.elvdev.buoyient.SyncableObjectServiceResponse$* { *; }
-keep class com.elvdev.buoyient.SyncableObjectServiceRequestState$* { *; }
-keep class com.elvdev.buoyient.SyncUpResult$* { *; }
