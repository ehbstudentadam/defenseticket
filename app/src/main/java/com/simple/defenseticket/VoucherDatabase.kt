package com.simple.defenseticket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.database.sqlite.transaction


enum class VoucherStatus {
    AVAILABLE,
    USED,
    INVALID
}


object VoucherStatusReason {

    const val PURCHASE_SUCCESS =
        "PURCHASE_SUCCESS"

    const val ALREADY_USED =
        "ALREADY_USED"

    const val INVALID_CODE =
        "INVALID_CODE"

    const val EXPIRED =
        "EXPIRED"
}


object VoucherErrorType {

    const val HTTP_ERROR =
        "HTTP_ERROR"

    const val NETWORK_ERROR =
        "NETWORK_ERROR"

    const val TEMPORARY_ERROR =
        "TEMPORARY_ERROR"

    const val UNKNOWN_ERROR =
        "UNKNOWN_ERROR"
}


data class Voucher(
    val code: String,
    val expirationDate: LocalDate,
    val status: VoucherStatus,
    val importedAt: Long,
    val usedAt: Long?,

    val statusReason: String? = null,
    val lastErrorType: String? = null,
    val lastError: String? = null,
    val lastCheckedAt: Long? = null,
    val failureCount: Int = 0
) {

    fun isExpired(
        today: LocalDate = LocalDate.now()
    ): Boolean {

        return expirationDate.isBefore(today)
    }


    fun isUsable(
        today: LocalDate = LocalDate.now()
    ): Boolean {

        return status == VoucherStatus.AVAILABLE &&
                !isExpired(today)
    }
}


data class ImportedVoucher(
    val code: String,
    val expirationDate: LocalDate
)


data class VoucherCsvParseResult(
    val vouchers: List<ImportedVoucher>,
    val invalidRows: Int,
    val duplicateRows: Int
)


data class VoucherImportResult(
    val inserted: Int,
    val updated: Int
)


class VoucherDatabaseHelper(
    context: Context
) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {

        private const val DATABASE_NAME =
            "defense_ticket.db"

        private const val DATABASE_VERSION =
            2

        private const val TABLE_VOUCHERS =
            "vouchers"

        private const val COLUMN_CODE =
            "code"

        private const val COLUMN_EXPIRATION_DATE =
            "expiration_date"

        private const val COLUMN_STATUS =
            "status"

        private const val COLUMN_IMPORTED_AT =
            "imported_at"

        private const val COLUMN_USED_AT =
            "used_at"

        private const val COLUMN_STATUS_REASON =
            "status_reason"

        private const val COLUMN_LAST_ERROR_TYPE =
            "last_error_type"

        private const val COLUMN_LAST_ERROR =
            "last_error"

        private const val COLUMN_LAST_CHECKED_AT =
            "last_checked_at"

        private const val COLUMN_FAILURE_COUNT =
            "failure_count"
    }


    override fun onCreate(
        db: SQLiteDatabase
    ) {

        db.execSQL(
            """
            CREATE TABLE $TABLE_VOUCHERS (
                $COLUMN_CODE TEXT PRIMARY KEY NOT NULL,
                $COLUMN_EXPIRATION_DATE TEXT NOT NULL,
                $COLUMN_STATUS TEXT NOT NULL,
                $COLUMN_IMPORTED_AT INTEGER NOT NULL,
                $COLUMN_USED_AT INTEGER,
                $COLUMN_STATUS_REASON TEXT,
                $COLUMN_LAST_ERROR_TYPE TEXT,
                $COLUMN_LAST_ERROR TEXT,
                $COLUMN_LAST_CHECKED_AT INTEGER,
                $COLUMN_FAILURE_COUNT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )


        db.execSQL(
            """
            CREATE INDEX idx_voucher_status
            ON $TABLE_VOUCHERS($COLUMN_STATUS)
            """.trimIndent()
        )


        db.execSQL(
            """
            CREATE INDEX idx_voucher_expiration
            ON $TABLE_VOUCHERS($COLUMN_EXPIRATION_DATE)
            """.trimIndent()
        )
    }


    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {

        /*
         * Migratie versie 1 → 2.
         *
         * Bestaande vouchers blijven behouden.
         */

        if (oldVersion < 2) {

            db.execSQL(
                """
                ALTER TABLE $TABLE_VOUCHERS
                ADD COLUMN $COLUMN_STATUS_REASON TEXT
                """.trimIndent()
            )

            db.execSQL(
                """
                ALTER TABLE $TABLE_VOUCHERS
                ADD COLUMN $COLUMN_LAST_ERROR_TYPE TEXT
                """.trimIndent()
            )

            db.execSQL(
                """
                ALTER TABLE $TABLE_VOUCHERS
                ADD COLUMN $COLUMN_LAST_ERROR TEXT
                """.trimIndent()
            )

            db.execSQL(
                """
                ALTER TABLE $TABLE_VOUCHERS
                ADD COLUMN $COLUMN_LAST_CHECKED_AT INTEGER
                """.trimIndent()
            )

            db.execSQL(
                """
                ALTER TABLE $TABLE_VOUCHERS
                ADD COLUMN $COLUMN_FAILURE_COUNT
                INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }


    /*
     * ========================================================
     * CSV IMPORT
     * ========================================================
     */

    fun importVouchers(
        importedVouchers: List<ImportedVoucher>
    ): VoucherImportResult {

        val db =
            writableDatabase

        var inserted =
            0

        var updated =
            0


        db.transaction {

            try {

                importedVouchers.forEach { voucher ->

                    val exists =
                        voucherExists(
                            db = this,
                            code = voucher.code
                        )


                    val now =
                        System.currentTimeMillis()


                    if (exists) {

                        /*
                     * BELANGRIJK:
                     *
                     * Een opnieuw geïmporteerde voucher mag
                     * nooit USED → AVAILABLE worden.
                     *
                     * We actualiseren dus alleen:
                     *
                     * expiration_date
                     * imported_at
                     */

                        val values =
                            ContentValues().apply {

                                put(
                                    COLUMN_EXPIRATION_DATE,
                                    voucher.expirationDate.toString()
                                )

                                put(
                                    COLUMN_IMPORTED_AT,
                                    now
                                )
                            }


                        update(
                            TABLE_VOUCHERS,
                            values,
                            "$COLUMN_CODE = ?",
                            arrayOf(
                                voucher.code
                            )
                        )

                        updated++

                    } else {

                        val values =
                            ContentValues().apply {

                                put(
                                    COLUMN_CODE,
                                    voucher.code
                                )

                                put(
                                    COLUMN_EXPIRATION_DATE,
                                    voucher.expirationDate.toString()
                                )

                                put(
                                    COLUMN_STATUS,
                                    VoucherStatus.AVAILABLE.name
                                )

                                put(
                                    COLUMN_IMPORTED_AT,
                                    now
                                )

                                putNull(
                                    COLUMN_USED_AT
                                )

                                putNull(
                                    COLUMN_STATUS_REASON
                                )

                                putNull(
                                    COLUMN_LAST_ERROR_TYPE
                                )

                                putNull(
                                    COLUMN_LAST_ERROR
                                )

                                putNull(
                                    COLUMN_LAST_CHECKED_AT
                                )

                                put(
                                    COLUMN_FAILURE_COUNT,
                                    0
                                )
                            }


                        insertOrThrow(
                            TABLE_VOUCHERS,
                            null,
                            values
                        )

                        inserted++
                    }
                }


            } finally {

            }
        }


        return VoucherImportResult(
            inserted = inserted,
            updated = updated
        )
    }


    private fun voucherExists(
        db: SQLiteDatabase,
        code: String
    ): Boolean {

        db.query(
            TABLE_VOUCHERS,

            arrayOf(
                COLUMN_CODE
            ),

            "$COLUMN_CODE = ?",

            arrayOf(
                code
            ),

            null,
            null,
            null,
            "1"
        ).use { cursor ->

            return cursor.moveToFirst()
        }
    }


    /*
     * ========================================================
     * ALLE VOUCHERS
     * ========================================================
     */

    fun getAllVouchers(): List<Voucher> {

        val result =
            mutableListOf<Voucher>()


        readableDatabase.query(
            TABLE_VOUCHERS,

            null,

            null,
            null,
            null,
            null,

            "$COLUMN_EXPIRATION_DATE ASC, $COLUMN_CODE ASC"
        ).use { cursor ->


            val codeIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_CODE
                )

            val expirationIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_EXPIRATION_DATE
                )

            val statusIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_STATUS
                )

            val importedAtIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_IMPORTED_AT
                )

            val usedAtIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_USED_AT
                )

            val statusReasonIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_STATUS_REASON
                )

            val lastErrorTypeIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_LAST_ERROR_TYPE
                )

            val lastErrorIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_LAST_ERROR
                )

            val lastCheckedIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_LAST_CHECKED_AT
                )

            val failureCountIndex =
                cursor.getColumnIndexOrThrow(
                    COLUMN_FAILURE_COUNT
                )


            while (
                cursor.moveToNext()
            ) {

                val expirationDate = try {

                    LocalDate.parse(
                        cursor.getString(
                            expirationIndex
                        )
                    )

                } catch (_: Exception) {

                    LocalDate.MIN
                }


                val status = try {

                    VoucherStatus.valueOf(
                        cursor.getString(
                            statusIndex
                        )
                    )

                } catch (_: Exception) {

                    VoucherStatus.INVALID
                }


                result.add(

                    Voucher(
                        code =
                            cursor.getString(
                                codeIndex
                            ),

                        expirationDate =
                            expirationDate,

                        status =
                            status,

                        importedAt =
                            cursor.getLong(
                                importedAtIndex
                            ),

                        usedAt =
                            cursor.getNullableLong(
                                usedAtIndex
                            ),

                        statusReason =
                            cursor.getNullableString(
                                statusReasonIndex
                            ),

                        lastErrorType =
                            cursor.getNullableString(
                                lastErrorTypeIndex
                            ),

                        lastError =
                            cursor.getNullableString(
                                lastErrorIndex
                            ),

                        lastCheckedAt =
                            cursor.getNullableLong(
                                lastCheckedIndex
                            ),

                        failureCount =
                            cursor.getInt(
                                failureCountIndex
                            )
                    )
                )
            }
        }


        return result
    }


    /*
     * ========================================================
     * VOLGENDE BRUIKBARE VOUCHER
     * ========================================================
     */

    fun getNextAvailableVoucher(
        excludedCodes: Set<String> = emptySet(),
        today: LocalDate = LocalDate.now()
    ): Voucher? {

        /*
         * Voor één boekingspoging kunnen we codes uitsluiten
         * die we al geprobeerd hebben.
         *
         * Zo blijven we nooit eindeloos dezelfde code proberen.
         */

        return getAllVouchers()
            .asSequence()
            .filter {
                it.isUsable(today)
            }
            .filter {
                it.code !in excludedCodes
            }.minByOrNull {
                it.expirationDate
            }
    }


    /*
     * ========================================================
     * VOUCHER WERD AAN MANDJE TOEGEVOEGD
     * ========================================================
     *
     * NIET als USED markeren!
     */

    fun markVoucherAcceptedByNmbs(
        code: String
    ) {

        val values =
            ContentValues().apply {

                put(
                    COLUMN_LAST_CHECKED_AT,
                    System.currentTimeMillis()
                )

                putNull(
                    COLUMN_LAST_ERROR_TYPE
                )

                putNull(
                    COLUMN_LAST_ERROR
                )
            }


        writableDatabase.update(
            TABLE_VOUCHERS,
            values,
            "$COLUMN_CODE = ?",
            arrayOf(
                code
            )
        )
    }


    /*
     * ========================================================
     * SUCCESVOL NIEUW TICKET GEKOCHT
     * ========================================================
     */

    fun markVoucherUsedAfterSuccessfulPurchase(
        code: String
    ) {

        val now =
            System.currentTimeMillis()


        val values =
            ContentValues().apply {

                put(
                    COLUMN_STATUS,
                    VoucherStatus.USED.name
                )

                put(
                    COLUMN_STATUS_REASON,
                    VoucherStatusReason.PURCHASE_SUCCESS
                )

                put(
                    COLUMN_USED_AT,
                    now
                )

                put(
                    COLUMN_LAST_CHECKED_AT,
                    now
                )

                putNull(
                    COLUMN_LAST_ERROR_TYPE
                )

                putNull(
                    COLUMN_LAST_ERROR
                )
            }


        writableDatabase.update(
            TABLE_VOUCHERS,
            values,
            "$COLUMN_CODE = ?",
            arrayOf(
                code
            )
        )
    }


    /*
     * ========================================================
     * NMBS ZEGT: DEZE CODE WERD AL GEBRUIKT
     * ========================================================
     */

    fun markVoucherAlreadyUsed(
        code: String,
        serverMessage: String
    ) {

        val values =
            ContentValues().apply {

                put(
                    COLUMN_STATUS,
                    VoucherStatus.USED.name
                )

                put(
                    COLUMN_STATUS_REASON,
                    VoucherStatusReason.ALREADY_USED
                )

                /*
                 * We weten niet WANNEER hij vroeger gebruikt
                 * werd, dus used_at vullen we bewust niet met
                 * de huidige tijd.
                 */

                put(
                    COLUMN_LAST_CHECKED_AT,
                    System.currentTimeMillis()
                )

                put(
                    COLUMN_LAST_ERROR_TYPE,
                    VoucherStatusReason.ALREADY_USED
                )

                put(
                    COLUMN_LAST_ERROR,
                    cleanServerMessage(
                        serverMessage
                    )
                )
            }


        writableDatabase.update(
            TABLE_VOUCHERS,
            values,
            "$COLUMN_CODE = ?",
            arrayOf(
                code
            )
        )
    }


    /*
     * ========================================================
     * NMBS ZEGT: ONGELDIGE CODE
     * ========================================================
     */

    fun markVoucherInvalid(
        code: String,
        serverMessage: String
    ) {

        val values =
            ContentValues().apply {

                put(
                    COLUMN_STATUS,
                    VoucherStatus.INVALID.name
                )

                put(
                    COLUMN_STATUS_REASON,
                    VoucherStatusReason.INVALID_CODE
                )

                put(
                    COLUMN_LAST_CHECKED_AT,
                    System.currentTimeMillis()
                )

                put(
                    COLUMN_LAST_ERROR_TYPE,
                    VoucherStatusReason.INVALID_CODE
                )

                put(
                    COLUMN_LAST_ERROR,
                    cleanServerMessage(
                        serverMessage
                    )
                )
            }


        writableDatabase.update(
            TABLE_VOUCHERS,
            values,
            "$COLUMN_CODE = ?",
            arrayOf(
                code
            )
        )
    }


    /*
     * ========================================================
     * VERLOPEN VOUCHER
     * ========================================================
     */

    fun markVoucherExpired(
        code: String,
        serverMessage: String
    ) {

        val values =
            ContentValues().apply {

                put(
                    COLUMN_STATUS,
                    VoucherStatus.INVALID.name
                )

                put(
                    COLUMN_STATUS_REASON,
                    VoucherStatusReason.EXPIRED
                )

                put(
                    COLUMN_LAST_CHECKED_AT,
                    System.currentTimeMillis()
                )

                put(
                    COLUMN_LAST_ERROR_TYPE,
                    VoucherStatusReason.EXPIRED
                )

                put(
                    COLUMN_LAST_ERROR,
                    cleanServerMessage(
                        serverMessage
                    )
                )
            }


        writableDatabase.update(
            TABLE_VOUCHERS,
            values,
            "$COLUMN_CODE = ?",
            arrayOf(
                code
            )
        )
    }


    /*
     * ========================================================
     * TIJDELIJKE / ONBEKENDE FOUT
     * ========================================================
     *
     * Voucher blijft AVAILABLE.
     *
     * We willen een geldige voucher niet permanent blokkeren
     * omdat bijvoorbeeld internet even wegviel.
     */

    fun recordVoucherFailure(
        code: String,
        errorType: String,
        errorMessage: String
    ) {

        writableDatabase.execSQL(
            """
            UPDATE $TABLE_VOUCHERS

            SET
                $COLUMN_LAST_ERROR_TYPE = ?,
                $COLUMN_LAST_ERROR = ?,
                $COLUMN_LAST_CHECKED_AT = ?,
                $COLUMN_FAILURE_COUNT =
                    $COLUMN_FAILURE_COUNT + 1

            WHERE
                $COLUMN_CODE = ?
            """.trimIndent(),

            arrayOf<Any>(
                errorType,
                cleanServerMessage(
                    errorMessage
                ),
                System.currentTimeMillis(),
                code
            )
        )
    }
}


/*
 * ============================================================
 * CURSOR HELPERS
 * ============================================================
 */

private fun android.database.Cursor.getNullableString(
    columnIndex: Int
): String? {

    return if (
        isNull(columnIndex)
    ) {
        null
    } else {
        getString(columnIndex)
    }
}


private fun android.database.Cursor.getNullableLong(
    columnIndex: Int
): Long? {

    return if (
        isNull(columnIndex)
    ) {
        null
    } else {
        getLong(columnIndex)
    }
}


/*
 * ============================================================
 * SERVER MESSAGE
 * ============================================================
 */

fun cleanServerMessage(
    message: String
): String {

    /*
     * Geen volledige HTML-pagina's in onze database opslaan.
     */

    return message
        .replace(
            Regex("\\s+"),
            " "
        )
        .trim()
        .take(500)
}


/*
 * ============================================================
 * CSV
 * ============================================================
 */

fun parseVoucherCsv(
    context: Context,
    uri: Uri
): VoucherCsvParseResult {

    val inputStream =
        context.contentResolver
            .openInputStream(uri)
            ?: throw IllegalArgumentException(
                "Bestand kon niet worden geopend."
            )


    inputStream
        .bufferedReader(
            Charsets.UTF_8
        )
        .use { reader ->


            var headerLine: String? =
                null


            while (true) {

                val line =
                    reader.readLine()
                        ?: break


                if (
                    line.isNotBlank()
                ) {

                    headerLine =
                        line.removePrefix(
                            "\uFEFF"
                        )

                    break
                }
            }


            if (
                headerLine == null
            ) {

                throw IllegalArgumentException(
                    "CSV-bestand is leeg."
                )
            }


            val headers =
                parseSemicolonCsvLine(
                    headerLine
                )


            val normalizedHeaders =
                headers.map {

                    it
                        .trim()
                        .lowercase(
                            Locale.ROOT
                        )
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                }


            val codeIndex =
                normalizedHeaders.indexOf(
                    "voucher code"
                )


            val expirationIndex =
                normalizedHeaders.indexOf(
                    "expiration date"
                )


            if (
                codeIndex == -1 ||
                expirationIndex == -1
            ) {

                throw IllegalArgumentException(
                    "CSV moet de kolommen " +
                            "'Voucher code' en " +
                            "'Expiration Date' bevatten."
                )
            }


            val uniqueVouchers =
                linkedMapOf<String, ImportedVoucher>()


            var invalidRows =
                0

            var duplicateRows =
                0


            reader.forEachLine { rawLine ->

                if (
                    rawLine.isBlank()
                ) {

                    return@forEachLine
                }


                val columns =
                    parseSemicolonCsvLine(
                        rawLine
                    )


                val requiredIndex =
                    maxOf(
                        codeIndex,
                        expirationIndex
                    )


                if (
                    columns.size <=
                    requiredIndex
                ) {

                    invalidRows++

                    return@forEachLine
                }


                val code =
                    columns[codeIndex]
                        .trim()
                        .uppercase(
                            Locale.ROOT
                        )


                val expirationText =
                    columns[expirationIndex]
                        .trim()


                val validCode =
                    code.length in 8..64 &&
                            code.all {

                                it.isLetterOrDigit() ||
                                        it == '-' ||
                                        it == '_'
                            }


                if (
                    !validCode
                ) {

                    invalidRows++

                    return@forEachLine
                }


                val expirationDate =
                    try {

                        LocalDate.parse(
                            expirationText,
                            DateTimeFormatter.ISO_LOCAL_DATE
                        )

                    } catch (_: Exception) {

                        invalidRows++

                        return@forEachLine
                    }


                if (
                    uniqueVouchers.containsKey(
                        code
                    )
                ) {

                    duplicateRows++
                }


                uniqueVouchers[code] =
                    ImportedVoucher(
                        code =
                            code,

                        expirationDate =
                            expirationDate
                    )
            }


            return VoucherCsvParseResult(
                vouchers =
                    uniqueVouchers.values.toList(),

                invalidRows =
                    invalidRows,

                duplicateRows =
                    duplicateRows
            )
        }
}


/*
 * ============================================================
 * SEMICOLON CSV PARSER
 * ============================================================
 */

fun parseSemicolonCsvLine(
    line: String
): List<String> {

    val fields =
        mutableListOf<String>()

    val current =
        StringBuilder()

    var insideQuotes =
        false

    var index =
        0


    while (
        index < line.length
    ) {

        val character =
            line[index]


        when (character) {
            '"' -> {

                if (
                    insideQuotes &&
                    index + 1 < line.length &&
                    line[index + 1] == '"'
                ) {

                    current.append(
                        '"'
                    )

                    index++

                } else {

                    insideQuotes =
                        !insideQuotes
                }
            }
            ';' if !insideQuotes -> {

                fields.add(
                    current
                        .toString()
                        .trim()
                )

                current.clear()
            }
            else -> {

                current.append(
                    character
                )
            }
        }


        index++
    }


    fields.add(
        current
            .toString()
            .trim()
    )


    return fields
}