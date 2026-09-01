package com.kharcha.ledger.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kharcha.ledger.data.db.LedgerDatabase
import com.kharcha.ledger.data.security.PassphraseStore
import com.kharcha.ledger.engine.insights.InsightsEngine
import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.merchant.MerchantCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideInsightsEngine(): InsightsEngine = InsightsEngine()

    /**
     * The database is opened through SQLCipher with a per-install random key
     * held in the Keystore-backed preference file. On a lost or stolen phone the
     * ledger is a meaningless blob without the device.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphrases: PassphraseStore
    ): LedgerDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, LedgerDatabase::class.java, LedgerDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrases.passphrase()))
            .addCallback(SeedCallback)
            .build()
    }

    /** Seeds the category list and the shipped merchant dictionary on first open. */
    private object SeedCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            Categories.all.forEachIndexed { index, name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, isCustom, sortOrder) VALUES (?, 0, ?)",
                    arrayOf<Any>(name, index)
                )
            }
            MerchantCatalog.entries.forEach { entry ->
                val id = "M_" + entry.canonicalName.uppercase().replace(" ", "_")
                db.execSQL(
                    "INSERT OR IGNORE INTO merchants (id, canonicalName, category, createdByUser) VALUES (?, ?, ?, 0)",
                    arrayOf<Any>(id, entry.canonicalName, entry.category)
                )
                (entry.aliases + entry.canonicalName).forEach { alias ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO merchant_aliases (alias, merchantId, createdByUser) VALUES (?, ?, 0)",
                        arrayOf<Any>(alias.uppercase(), id)
                    )
                }
            }
        }
    }
}
