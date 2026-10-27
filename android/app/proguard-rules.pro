# Certificate algorithms use provider-name lookup; retain the cryptographic provider.
-keep class org.bouncycastle.** { *; }
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
