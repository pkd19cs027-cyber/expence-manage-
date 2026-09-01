package com.kharcha.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsert(transactions: List<TransactionEntity>)

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp DESC")
    suspend fun since(from: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>
}

@Dao
interface TransactionSourceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sources: List<TransactionSourceEntity>)

    @Query("SELECT * FROM transaction_sources WHERE transactionId = :transactionId ORDER BY receivedAt")
    fun observeFor(transactionId: String): Flow<List<TransactionSourceEntity>>

    @Query("SELECT * FROM transaction_sources WHERE transactionId = :transactionId")
    suspend fun forTransaction(transactionId: String): List<TransactionSourceEntity>

    @Query("SELECT * FROM transaction_sources WHERE transactionId IN (:transactionIds)")
    suspend fun forTransactions(transactionIds: List<String>): List<TransactionSourceEntity>

    @Query("UPDATE transaction_sources SET transactionId = :toId WHERE transactionId = :fromId")
    suspend fun reassign(fromId: String, toId: String)
}

@Dao
interface TransactionLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(links: List<TransactionLinkEntity>)

    @Query("SELECT * FROM transaction_links WHERE fromTransactionId = :id OR toTransactionId = :id")
    fun observeFor(id: String): Flow<List<TransactionLinkEntity>>

    @Query("SELECT * FROM transaction_links WHERE fromTransactionId = :id OR toTransactionId = :id")
    suspend fun linksFor(id: String): List<TransactionLinkEntity>

    @Query("DELETE FROM transaction_links WHERE fromTransactionId = :from AND toTransactionId = :to AND relation = :relation")
    suspend fun delete(from: String, to: String, relation: String)
}

@Dao
interface AccountDao {

    @Upsert
    suspend fun upsert(accounts: List<AccountEntity>)

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts ORDER BY verifiedByUser DESC, displayName")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE verifiedByUser = 1")
    suspend fun verified(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE verifiedByUser = 0 AND hiddenByUser = 0 ORDER BY occurrences DESC")
    fun observeUnverified(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE accountKey = :key")
    suspend fun byKey(key: String): AccountEntity?

    @Query("UPDATE accounts SET verifiedByUser = :verified, hiddenByUser = :hidden WHERE accountKey = :key")
    suspend fun setVerification(key: String, verified: Boolean, hidden: Boolean)

    @Query("UPDATE accounts SET displayName = :name WHERE accountKey = :key")
    suspend fun rename(key: String, name: String)
}

@Dao
interface ReviewDao {

    @Upsert
    suspend fun upsert(items: List<ReviewItemEntity>)

    @Query("SELECT * FROM review_items WHERE resolvedAt IS NULL ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_items WHERE resolvedAt IS NULL")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT * FROM review_items WHERE id = :id")
    suspend fun byId(id: String): ReviewItemEntity?

    @Query("UPDATE review_items SET resolvedAt = :at, resolvedAction = :action WHERE id = :id")
    suspend fun resolve(id: String, action: String, at: Long)

    @Query("UPDATE review_items SET resolvedAt = :at, resolvedAction = 'AUTO' WHERE transactionId = :transactionId AND resolvedAt IS NULL")
    suspend fun resolveAllFor(transactionId: String, at: Long)
}

@Dao
interface RuleDao {

    @Upsert
    suspend fun upsert(rule: RuleEntity)

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MerchantDao {

    @Upsert
    suspend fun upsertMerchant(merchant: MerchantEntity)

    @Upsert
    suspend fun upsertAlias(alias: MerchantAliasEntity)

    @Query("SELECT * FROM merchants ORDER BY canonicalName")
    fun observeAll(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchant_aliases WHERE alias = :alias")
    suspend fun aliasOf(alias: String): MerchantAliasEntity?
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>
}

@Dao
interface BudgetDao {

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
abstract class RecurringDao {

    @Upsert
    abstract suspend fun upsert(patterns: List<RecurringPatternEntity>)

    @Query("SELECT * FROM recurring_patterns ORDER BY expectedPaise DESC")
    abstract fun observeAll(): Flow<List<RecurringPatternEntity>>

    @Query("DELETE FROM recurring_patterns")
    abstract suspend fun clear()

    /** Patterns are always recomputed wholesale, so stale ones cannot linger. */
    @Transaction
    open suspend fun replaceAll(patterns: List<RecurringPatternEntity>) {
        clear()
        upsert(patterns)
    }
}

@Dao
interface MessageDao {

    @Upsert
    suspend fun upsert(messages: List<ProcessedMessageEntity>)

    @Query("SELECT sourceId FROM processed_messages WHERE sourceType = :sourceType")
    suspend fun processedIds(sourceType: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM processed_messages WHERE messageHash = :hash)")
    suspend fun isProcessed(hash: String): Boolean

    @Query("SELECT * FROM processed_messages WHERE parserVersion < :version ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun olderThanParser(version: Int, limit: Int): List<ProcessedMessageEntity>

    @Query("SELECT COUNT(*) FROM processed_messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processed_messages WHERE transactionId IS NULL")
    fun observeIgnoredCount(): Flow<Int>

    @Query("SELECT MAX(receivedAt) FROM processed_messages")
    suspend fun lastProcessedAt(): Long?

    @Query("DELETE FROM processed_messages WHERE messageHash IN (:hashes)")
    suspend fun deleteProcessed(hashes: List<String>)

    @Query("SELECT COUNT(*) FROM processed_messages WHERE parserVersion < :version")
    suspend fun outdatedCount(version: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun keepRaw(messages: List<RawMessageEntity>)

    @Query("SELECT * FROM raw_messages WHERE messageHash = :hash")
    suspend fun raw(hash: String): RawMessageEntity?

    @Query("DELETE FROM raw_messages")
    suspend fun purgeRaw()
}
