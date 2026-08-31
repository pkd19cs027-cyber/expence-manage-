# Room and the engine's data classes are reflected over by name.
-keep class com.kharcha.ledger.data.db.entity.** { *; }
-keep class com.kharcha.ledger.engine.model.** { *; }

# SQLCipher loads its native library by name.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
