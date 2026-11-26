package com.jesusluna.bolsilloplus

import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper centralizado para generar dedupKey canónica en tests.
 * Usa exactamente la misma lógica que TransactionRepository.importTransactions()
 */
object TestDedupKey {
    
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Europe/Madrid")
    }
    
    fun build(
        normalizedConcept: String,
        amount: Double,
        date: Date,
        hasBalance: Boolean,
        balance: Double
    ): String {
        // Sanitizar -0.0 a 0.0 para consistencia
        val sanitizedAmount = if (amount == 0.0) 0.0 else amount
        val sanitizedBalance = if (balance == 0.0) 0.0 else balance
        
        val amountStr = String.format(Locale.ROOT, "%.2f", sanitizedAmount)
        val dateStr = dateFormat.format(date)
        val balanceOrUnderscore = if (hasBalance) String.format(Locale.ROOT, "%.2f", sanitizedBalance) else "_"
        return "$normalizedConcept|$amountStr|$dateStr|$balanceOrUnderscore"
    }
    
    fun buildFromConcept(
        concept: String,
        amount: Double,
        dateMs: Long,
        hasBalance: Boolean,
        balance: Double
    ): String {
        val normalizedConcept = DescriptionNormalizer.normalize(concept)
        return build(normalizedConcept, amount, Date(dateMs), hasBalance, balance)
    }
    
    fun buildForTransaction(tx: com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity): String {
        return build(
            normalizedConcept = tx.normalizedConcept,
            amount = tx.amount,
            date = Date(tx.date),
            hasBalance = tx.hasBalance,
            balance = tx.balance
        )
    }
}
