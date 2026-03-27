# syncable-objects-hilt consumer ProGuard rules

# Keep Hilt-generated components and modules that register SyncableObjectService instances
-keep class com.les.buoyient.hilt.** { *; }
