# R8 rules for Goo.
#
# House warning (AGENTS.md): "works in debug, breaks in release" is almost
# always a missing keep rule for a new reflection/serialization entry point —
# check here first.

# kotlinx.serialization — keep generated serializers for our own classes
# (stroke logs / project files are serialized starting with roadmap step 2;
# navigation route objects are serialized today).
-keepclassmembers class ch.lkmc.goo.** {
    *** Companion;
}
-keepclasseswithmembers class ch.lkmc.goo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
