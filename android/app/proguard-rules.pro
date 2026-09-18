# kotlinx.serialization: keep serializers for our data classes
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class uk.co.promptbuilt.notestodos.** {
    *** Companion;
}
-keepclasseswithmembers class uk.co.promptbuilt.notestodos.** {
    kotlinx.serialization.KSerializer serializer(...);
}
