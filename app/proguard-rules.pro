# Chaquopy: Python-классы и JNI-мост не минифицируем
-keep class com.chaquo.python.** { *; }

# org.json используется мостом
-keep class org.json.** { *; }
