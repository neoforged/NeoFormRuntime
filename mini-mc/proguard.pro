-dontoptimize
#-dontpreverify
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn com.google.gson.**
-ignorewarnings

# Keep - Applications. Keep all application classes, along with their 'main'
# methods.
-keepclasseswithmembers public class * {
    public static void main(java.lang.String[]);
}
