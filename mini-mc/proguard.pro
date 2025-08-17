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

# Put everything into the root package, same as MC
-repackageclasses
# Try to keep parameter names?
-keepparameternames
# Keep attributes?
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,Record,LineNumberTable,LocalVariableTypeTable
# Also the LVT itself?
-keepattributes LocalVariableTable
