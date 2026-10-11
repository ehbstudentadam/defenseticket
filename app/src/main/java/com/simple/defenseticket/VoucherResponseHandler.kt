package com.simple.defenseticket


enum class VoucherAddResultType {

    ADDED,

    ALREADY_USED,

    INVALID,

    EXPIRED,

    TEMPORARY_ERROR,

    UNKNOWN_ERROR
}


data class VoucherAddResult(
    val type: VoucherAddResultType,
    val shouldTryNextVoucher: Boolean,
    val message: String
)


object VoucherResponseHandler {


    fun handleAddVoucherResponse(
        database: VoucherDatabaseHelper,
        voucherCode: String,
        httpStatus: Int,
        responseBody: String
    ): VoucherAddResult {

        val cleanBody =
            cleanServerMessage(
                responseBody
            )


        val normalized =
            cleanBody.lowercase()


        /*
         * ====================================================
         * SUCCESVOL AAN MANDJE TOEGEVOEGD
         * ====================================================
         *
         * Nog NIET USED maken.
         */

        if (
            httpStatus in 200..299
        ) {

            database.markVoucherAcceptedByNmbs(
                voucherCode
            )


            return VoucherAddResult(
                type =
                    VoucherAddResultType.ADDED,

                shouldTryNextVoucher =
                    false,

                message =
                    "Voucher geaccepteerd."
            )
        }


        /*
         * ====================================================
         * REEDS GEBRUIKT
         * ====================================================
         */

        val alreadyUsed =
            normalized.contains(
                "kan maar één keer gebruikt worden"
            ) ||
                    normalized.contains(
                        "deze code werd al gebruikt"
                    ) ||
                    normalized.contains(
                        "reeds gebruikt"
                    ) ||
                    normalized.contains(
                        "al gebruikt"
                    )


        if (
            alreadyUsed
        ) {

            database.markVoucherAlreadyUsed(
                code =
                    voucherCode,

                serverMessage =
                    cleanBody
            )


            return VoucherAddResult(
                type =
                    VoucherAddResultType.ALREADY_USED,

                shouldTryNextVoucher =
                    true,

                message =
                    "Voucher was al gebruikt. Volgende voucher wordt geprobeerd."
            )
        }


        /*
         * ====================================================
         * ONGELDIGE CODE
         * ====================================================
         */

        val invalid =
            normalized.contains(
                "ongeldige code"
            ) ||
                    normalized.contains(
                        "ongeldige vouchercode"
                    )


        if (
            invalid
        ) {

            database.markVoucherInvalid(
                code =
                    voucherCode,

                serverMessage =
                    cleanBody
            )


            return VoucherAddResult(
                type =
                    VoucherAddResultType.INVALID,

                shouldTryNextVoucher =
                    true,

                message =
                    "Voucher is ongeldig. Volgende voucher wordt geprobeerd."
            )
        }


        /*
         * ====================================================
         * VERLOPEN
         * ====================================================
         */

        val expired =
            normalized.contains(
                "vervallen"
            ) ||
                    normalized.contains(
                        "verlopen"
                    ) ||
                    normalized.contains(
                        "expired"
                    )


        if (
            expired
        ) {

            database.markVoucherExpired(
                code =
                    voucherCode,

                serverMessage =
                    cleanBody
            )


            return VoucherAddResult(
                type =
                    VoucherAddResultType.EXPIRED,

                shouldTryNextVoucher =
                    true,

                message =
                    "Voucher is verlopen. Volgende voucher wordt geprobeerd."
            )
        }


        /*
         * ====================================================
         * TIJDELIJKE HTTP FOUTEN
         * ====================================================
         */

        if (
            httpStatus == 408 ||
            httpStatus == 429 ||
            httpStatus >= 500
        ) {

            database.recordVoucherFailure(
                code =
                    voucherCode,

                errorType =
                    VoucherErrorType.TEMPORARY_ERROR,

                errorMessage =
                    "HTTP $httpStatus: $cleanBody"
            )


            /*
             * Hier NIET automatisch volgende voucher proberen.
             *
             * Anders zouden we bij een NMBS-storing tientallen
             * vouchers na elkaar proberen.
             */

            return VoucherAddResult(
                type =
                    VoucherAddResultType.TEMPORARY_ERROR,

                shouldTryNextVoucher =
                    false,

                message =
                    "Tijdelijke NMBS-fout. Voucher blijft beschikbaar."
            )
        }


        /*
         * ====================================================
         * ONBEKENDE FOUT
         * ====================================================
         */

        database.recordVoucherFailure(
            code =
                voucherCode,

            errorType =
                VoucherErrorType.UNKNOWN_ERROR,

            errorMessage =
                "HTTP $httpStatus: $cleanBody"
        )


        return VoucherAddResult(
            type =
                VoucherAddResultType.UNKNOWN_ERROR,

            shouldTryNextVoucher =
                false,

            message =
                "Onbekende fout. Voucherstatus werd niet gewijzigd."
        )
    }


    /*
     * ========================================================
     * NETWERKFOUT
     * ========================================================
     */

    fun handleNetworkFailure(
        database: VoucherDatabaseHelper,
        voucherCode: String,
        error: Throwable
    ): VoucherAddResult {

        database.recordVoucherFailure(
            code =
                voucherCode,

            errorType =
                VoucherErrorType.NETWORK_ERROR,

            errorMessage =
                error.message
                    ?: "Onbekende netwerkfout"
        )


        return VoucherAddResult(
            type =
                VoucherAddResultType.TEMPORARY_ERROR,

            shouldTryNextVoucher =
                false,

            message =
                "Netwerkfout. Voucher blijft beschikbaar."
        )
    }
}