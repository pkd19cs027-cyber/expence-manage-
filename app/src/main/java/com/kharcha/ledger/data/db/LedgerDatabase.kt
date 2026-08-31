package com.kharcha.ledger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kharcha.ledger.data.db.dao.AccountDao
import com.kharcha.ledger.data.db.dao.BudgetDao
import com.kharcha.ledger.data.db.dao.CategoryDao
import com.kharcha.ledger.data.db.dao.MerchantDao
import com.kharcha.ledger.data.db.dao.MessageDao
import com.kharcha.ledger.data.db.dao.RecurringDao
import com.kharcha.ledger.data.db.dao.ReviewDao
import com.kharcha.ledger.data.db.dao.RuleDao
import com.kharcha.ledger.data.db.dao.TransactionDao
import com.kharcha.ledger.data.db.dao.TransactionLinkDao
import com.kharcha.ledger.data.db.dao.TransactionSourceDao
import com.kharcha.ledger.data.db.entity.AccountEntity
import com.kharcha.ledger.data.db.entity.BudgetEntity
import com.kharcha.ledger.data.db.entity.CategoryEntity
import com.kharcha.ledger.data.db.entity.MerchantAliasEntity
import com.kharcha.ledger.data.db.entity.MerchantEntity
import com.kharcha.ledger.data.db.entity.ProcessedMessageEntity
import com.kharcha.ledger.data.db.entity.RawMessageEntity
import com.kharcha.ledger.data.db.entity.RecurringPatternEntity
import com.kharcha.ledger.data.db.entity.ReviewItemEntity
import com.kharcha.ledger.data.db.entity.RuleEntity
import com.kharcha.ledger.data.db.entity.TransactionEntity
import com.kharcha.ledger.data.db.entity.TransactionLinkEntity
import com.kharcha.ledger.data.db.entity.TransactionSourceEntity

/**
 * The whole ledger, encrypted, on the phone. There is no server counterpart and
 * no sync: this file is the system of record.
 */
@Database(
    entities = [
        TransactionEntity::class,
        TransactionSourceEntity::class,
        TransactionLinkEntity::class,
        AccountEntity::class,
        MerchantEntity::class,
        MerchantAliasEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        RecurringPatternEntity::class,
        BudgetEntity::class,
        ReviewItemEntity::class,
        ProcessedMessageEntity::class,
        RawMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun sourceDao(): TransactionSourceDao
    abstract fun linkDao(): TransactionLinkDao
    abstract fun accountDao(): AccountDao
    abstract fun reviewDao(): ReviewDao
    abstract fun ruleDao(): RuleDao
    abstract fun merchantDao(): MerchantDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "kharcha-ledger.db"
    }
}
