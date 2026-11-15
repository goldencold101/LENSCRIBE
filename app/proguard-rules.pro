# Lenscribe Release R8 / ProGuard Rules

# Preserve line numbers and source names for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Firestore POJO models used with DocumentSnapshot.toObject() / toObjects()
-keepclassmembers class com.example.model.** {
    public <init>(...);
    <fields>;
    <methods>;
}
-keep class com.example.model.** { *; }

# Keep Room SQLite entities and database implementation
-keep class com.example.backup.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Firebase Firestore / Auth / App Check / AI annotations
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
    @com.google.firebase.firestore.Exclude <fields>;
    @com.google.firebase.firestore.PropertyName <fields>;
}
