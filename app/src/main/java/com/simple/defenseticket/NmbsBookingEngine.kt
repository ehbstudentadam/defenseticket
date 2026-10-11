package com.simple.defenseticket

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class NmbsBookingEngine(
    private val database: VoucherDatabaseHelper
) {

    companion object {
        private const val TICKETING_SEARCH_URL =
            "https://www.belgiantrain.be/nl/ticketing-search"

        private const val DEFENSE_PRODUCT_ID =
            "ML0"

        private const val DEFENSE_LIST_SEARCH_PRODUCT =
            "{2C24F905-0EEF-4705-B018-EC8E32D18AD5}"

        private val PAGE_TIMEOUT =
            45.seconds

        private val STATION_TIMEOUT =
            20.seconds

        private val PRODUCT_TIMEOUT =
            20.seconds

        private val REQUEST_TIMEOUT =
            60.seconds

        private val BASKET_ACTION_TIMEOUT =
            15.seconds

        private val BASKET_STATUS_TIMEOUT =
            10.seconds

        private val BROWSER_CHECK_TIMEOUT =
            5.minutes

        private val WEBVIEW_ATTACH_TIMEOUT =
            5.seconds
    }


    private data class PageEvent(
        val sequence: Long,
        val url: String
    )


    private data class StationPair(
        val departureReference: String,
        val arrivalReference: String
    )


    private data class VoucherHttpResult(
        val status: Int,
        val body: String,
        val basketGuid: String
    )


    private data class ActiveVoucher(
        val code: String,
        val basketGuid: String
    )


    private data class CheckoutResult(
        val success: Boolean,
        val finalUrl: String,
        val validation: String,
        val title: String
    )


    private class RequestEngineException(
        val errorType: String,
        message: String
    ) : Exception(message)


    private var webView: WebView? =
        null

    private var pageSequence =
        0L


    private val pageEvents =
        MutableSharedFlow<PageEvent>(
            replay = 1,
            extraBufferCapacity = 32
        )


    private val stationReferenceCache =
        mutableMapOf<String, String>()


    private val _state =
        MutableStateFlow(
            BookingUiState()
        )


    val state: StateFlow<BookingUiState> =
        _state.asStateFlow()


    fun attach(
        view: WebView
    ) {
        webView =
            view

        view.webViewClient =
            object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    url?.let { finishedUrl ->
                        pageSequence++

                        pageEvents.tryEmit(
                            PageEvent(
                                sequence =
                                    pageSequence,
                                url =
                                    finishedUrl
                            )
                        )
                    }
                }
            }
    }


    fun detach(
        view: WebView?
    ) {
        if (webView === view) {
            webView =
                null
        }
    }


    fun reset() {
        _state.value =
            BookingUiState()
    }


    suspend fun book(
        request: BookingRequest
    ) {
        if (_state.value.isRunning) {
            return
        }

        publish(
            phase =
                BookingPhase.STARTING,
            message =
                "NMBS-sessie starten...",
            running =
                true
        )

        try {
            validateBookingDates(
                request
            )

            awaitWebView()

            /*
             * Eén normale NMBS-pagina laden zodat de WebView
             * dezelfde browsercontext/cookies gebruikt.
             *
             * Geen anti-bot bypass. Als NMBS menselijke
             * verificatie vraagt, tonen we die WebView.
             */
            ensureSession()

            val trips =
                mutableListOf(
                    SingleTripRequest(
                        departure =
                            request.departure,
                        destination =
                            request.destination,
                        date =
                            request.departureDate
                    )
                )

            if (request.returnDate != null) {
                trips.add(
                    SingleTripRequest(
                        departure =
                            request.destination,
                        destination =
                            request.departure,
                        date =
                            request.returnDate
                    )
                )
            }

            trips.forEachIndexed { index, trip ->

                appendLog(
                    if (index == 0) {
                        "Heenrit starten."
                    } else {
                        "Terugrit starten."
                    }
                )

                bookSingleTrip(
                    request =
                        request,
                    trip =
                        trip,
                    tripNumber =
                        index + 1,
                    hasNextTrip =
                        index < trips.lastIndex
                )
            }

            publish(
                phase =
                    BookingPhase.SUCCESS,
                message =
                    if (trips.size == 1) {
                        "Ticket succesvol besteld."
                    } else {
                        "Heen- en terugticket succesvol besteld."
                    },
                running =
                    false
            )

            appendLog(
                "Bestelling voltooid."
            )

        } catch (
            exception: Exception
        ) {
            publish(
                phase =
                    BookingPhase.FAILED,
                message =
                    exception.message
                        ?: "Boeking mislukt.",
                running =
                    false
            )

            appendLog(
                "Boeking gestopt."
            )
        }
    }


    private suspend fun ensureSession() {
        val currentUrl =
            withContext(
                Dispatchers.Main
            ) {
                webView?.url
            }

        if (
            currentUrl == null ||
            !currentUrl.contains(
                "belgiantrain.be"
            )
        ) {
            loadPage(
                url =
                    TICKETING_SEARCH_URL,
                expectedUrl = {
                    it.contains(
                        "belgiantrain.be"
                    )
                }
            )
        }

        handleBrowserCheck(
            BookingPhase.STARTING
        )
    }


    private fun validateBookingDates(
        request: BookingRequest
    ) {
        validateSingleDate(
            request.departureDate,
            "heenrit"
        )

        request.returnDate?.let { returnDate ->

            validateSingleDate(
                returnDate,
                "terugrit"
            )

            if (
                returnDate.isBefore(
                    request.departureDate
                )
            ) {
                throw IllegalArgumentException(
                    "De terugrit kan niet vóór de heenrit liggen."
                )
            }
        }
    }


    private fun validateSingleDate(
        date: LocalDate,
        description: String
    ) {
        val daysAhead =
            ChronoUnit.DAYS.between(
                LocalDate.now(),
                date
            )

        if (daysAhead < 0) {
            throw IllegalArgumentException(
                "De datum voor de $description ligt in het verleden."
            )
        }

        if (daysAhead > 30) {
            throw IllegalArgumentException(
                "De datum voor de $description ligt meer dan 30 dagen in de toekomst."
            )
        }
    }


    private suspend fun bookSingleTrip(
        request: BookingRequest,
        trip: SingleTripRequest,
        tripNumber: Int,
        hasNextTrip: Boolean
    ) {
        /*
         * ========================================================
         * BASKET GUARD
         * ========================================================
         *
         * Een vorige mislukte checkout mag NOOIT een voucher-item
         * in het NMBS-mandje laten staan. Anders kan één volgende
         * checkout meerdere tickets tegelijk afleveren.
         */
        ensureBasketCleanBeforeTrip()


        publish(
            phase =
                BookingPhase.SEARCHING,
            message =
                "Stationcodes bepalen...",
            running =
                true
        )

        val stationPair =
            resolveStationReferences(
                trip.departure,
                trip.destination
            )

        appendLog(
            "Stationcodes gevonden."
        )


        val ticketsListUrl =
            buildTicketsListUrl(
                departureReference =
                    stationPair.departureReference,
                arrivalReference =
                    stationPair.arrivalReference,
                date =
                    trip.date
            )


        publish(
            phase =
                BookingPhase.SEARCHING,
            message =
                "Defense Ticket laden...",
            running =
                true
        )

        loadPage(
            url =
                ticketsListUrl,
            expectedUrl = {
                it.contains(
                    "/tickets-list"
                )
            }
        )

        handleBrowserCheck(
            BookingPhase.SEARCHING
        )

        if (
            !waitForDefenseTicket()
        ) {
            throw IllegalStateException(
                "Defense Ticket (ML0) werd niet gevonden voor deze reis."
            )
        }

        appendLog(
            "Defense Ticket gevonden."
        )


        /*
         * findWorkingVoucher() geeft zowel de code als, indien
         * beschikbaar, de basket GUID terug. Die GUID gebruiken we
         * om bij een mislukte checkout exact dit item te verwijderen.
         */
        val activeVoucher =
            findWorkingVoucher()

        appendLog(
            "Voucher geaccepteerd."
        )


        publish(
            phase =
                BookingPhase.CHECKOUT,
            message =
                "Bestelling afronden...",
            running =
                true
        )


        val checkout =
            try {
                performCheckoutByRequests(
                    request
                )

            } catch (
                exception: RequestEngineException
            ) {
                /*
                 * Een fout VOOR of tijdens een duidelijk geweigerde
                 * checkout mag het voucher-item niet laten hangen.
                 *
                 * Bij een netwerkfout tijdens de POST zelf is de
                 * serverstatus potentieel onzeker. In dat geval doen
                 * we geen automatische cleanup/retry: de gebruiker
                 * moet eerst controleren of er toch een e-mail kwam.
                 */
                if (
                    exception.errorType !=
                    "CHECKOUT_POST_STATUS_UNKNOWN"
                ) {
                    cleanupFailedVoucher(
                        activeVoucher
                    )
                }

                if (
                    exception.errorType ==
                    "CHECKOUT_POST_STATUS_UNKNOWN"
                ) {
                    throw IllegalStateException(
                        "De checkoutstatus is onzeker omdat de verbinding tijdens " +
                                "de definitieve bestelling wegviel. Boek NIET opnieuw " +
                                "voordat je gecontroleerd hebt of NMBS toch een ticket " +
                                "per e-mail heeft gestuurd."
                    )
                }

                throw exception

            } catch (
                exception: Exception
            ) {
                cleanupFailedVoucher(
                    activeVoucher
                )

                throw exception
            }


        if (
            !checkout.success
        ) {
            /*
             * Server gaf een duidelijk niet-succes antwoord:
             * verwijder exact het toegevoegde voucher-item.
             */
            cleanupFailedVoucher(
                activeVoucher
            )

            throw IllegalStateException(
                if (
                    checkout.validation.isNotBlank()
                ) {
                    "NMBS-checkout geweigerd: ${checkout.validation}"
                } else {
                    "NMBS-checkout werd niet succesvol afgerond. " +
                            "Pagina: ${checkout.title}"
                }
            )
        }


        /*
         * Alleen na betrouwbaar bevestigde success:
         * voucher = USED.
         */
        withContext(
            Dispatchers.IO
        ) {
            database
                .markVoucherUsedAfterSuccessfulPurchase(
                    activeVoucher.code
                )
        }


        appendLog(
            if (
                tripNumber == 1
            ) {
                "Ticket bevestigd door NMBS."
            } else {
                "Terugticket bevestigd door NMBS."
            }
        )


        /*
         * fetch() voert de JavaScript op de succespagina niet uit.
         * De echte NMBS-frontend roept na succes zelf
         * GET /API/Basket/EmptyTheBasket aan.
         *
         * Wij doen dezelfde cleanup expliciet en verifiëren daarna
         * dat geen voucher-item meer in het mandje zit.
         */
        val cleaned =
            emptyBasketAndVerify()

        if (
            !cleaned
        ) {
            throw IllegalStateException(
                "Het ticket is succesvol besteld, maar het NMBS-winkelmandje " +
                        "kon niet betrouwbaar worden leeggemaakt. Start geen nieuwe " +
                        "boeking voordat de app opnieuw is gestart."
            )
        }


        if (
            hasNextTrip
        ) {
            delay(
                750.milliseconds
            )
        }
    }


    /*
     * ============================================================
     * STATION REFERENCES
     * ============================================================
     *
     * Dit is het ENIGE deel waarvoor we nog de NMBS frontend
     * gebruiken. Zodra we de numerieke stationcodes hebben,
     * gebeurt de rest rechtstreeks via URL's/requests.
     */
    private suspend fun resolveStationReferences(
        departure: String,
        arrival: String
    ): StationPair {
        val departureKey =
            departure
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        val arrivalKey =
            arrival
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        val cachedDeparture =
            stationReferenceCache[
                departureKey
            ]

        val cachedArrival =
            stationReferenceCache[
                arrivalKey
            ]

        if (
            cachedDeparture != null &&
            cachedArrival != null
        ) {
            return StationPair(
                departureReference =
                    cachedDeparture,
                arrivalReference =
                    cachedArrival
            )
        }

        loadPage(
            url =
                TICKETING_SEARCH_URL,
            expectedUrl = {
                it.contains(
                    "/ticketing-search"
                )
            }
        )

        handleBrowserCheck(
            BookingPhase.SEARCHING
        )

        evaluateRaw(
            """
            window.__defenseTicketStationResult = null;
            """.trimIndent()
        )

        evaluateRaw(
            buildStationResolveScript(
                departure,
                arrival
            )
        )

        val result =
            waitForJavascriptJson(
                variable =
                    "window.__defenseTicketStationResult",
                timeout =
                    STATION_TIMEOUT
            )

        if (
            result.optBoolean(
                "error",
                false
            )
        ) {
            throw IllegalStateException(
                result.optString(
                    "message",
                    "NMBS-stationcodes konden niet worden bepaald."
                )
            )
        }

        val departureReference =
            result.optString(
                "departureReference"
            )

        val arrivalReference =
            result.optString(
                "arrivalReference"
            )

        if (
            departureReference.isBlank() ||
            arrivalReference.isBlank()
        ) {
            throw IllegalStateException(
                "NMBS-stationcodes konden niet worden bepaald."
            )
        }

        stationReferenceCache[
            departureKey
        ] =
            departureReference

        stationReferenceCache[
            arrivalKey
        ] =
            arrivalReference

        return StationPair(
            departureReference =
                departureReference,
            arrivalReference =
                arrivalReference
        )
    }


    private fun buildTicketsListUrl(
        departureReference: String,
        arrivalReference: String,
        date: LocalDate
    ): String {
        val displayDate =
            date.format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
                )
            )

        return "https://www.belgiantrain.be/nl/tickets-list"
            .toUri()
            .buildUpon()
            .appendQueryParameter(
                "DepartureStationReference",
                departureReference
            )
            .appendQueryParameter(
                "ArrivalStationReference",
                arrivalReference
            )
            .appendQueryParameter(
                "TravelClassCode",
                "2"
            )
            .appendQueryParameter(
                "JourneyTypeCode",
                "1"
            )
            .appendQueryParameter(
                "DepartureDate",
                displayDate
            )
            .appendQueryParameter(
                "ReturnDate",
                displayDate
            )
            .appendQueryParameter(
                "HighlightProductId",
                DEFENSE_PRODUCT_ID
            )
            .appendQueryParameter(
                "sfs",
                "1"
            )
            .appendQueryParameter(
                "listsearch_product",
                DEFENSE_LIST_SEARCH_PRODUCT
            )
            .appendQueryParameter(
                "rp",
                ""
            )
            .appendQueryParameter(
                "HighlightProductElementId",
                "highlightProductId_hidden"
            )
            .build()
            .toString()
    }


    /*
     * ============================================================
     * VOUCHERS
     * ============================================================
     */
    private suspend fun findWorkingVoucher(): ActiveVoucher {
        val excludedCodes =
            mutableSetOf<String>()

        var attempt =
            0


        while (
            true
        ) {
            val voucher =
                withContext(
                    Dispatchers.IO
                ) {
                    database
                        .getNextAvailableVoucher(
                            excludedCodes =
                                excludedCodes
                        )
                }
                    ?: throw IllegalStateException(
                        "Geen bruikbare vouchers meer beschikbaar."
                    )


            attempt++


            publish(
                phase =
                    BookingPhase.CHECKING_VOUCHER,
                message =
                    "Voucher $attempt controleren...",
                running =
                    true
            )

            appendLog(
                "Voucher $attempt controleren."
            )


            val httpResult =
                try {
                    addVoucher(
                        voucher.code
                    )

                } catch (
                    exception: RequestEngineException
                ) {
                    withContext(
                        Dispatchers.IO
                    ) {
                        database.recordVoucherFailure(
                            code =
                                voucher.code,
                            errorType =
                                exception.errorType,
                            errorMessage =
                                exception.message
                                    ?: "Technische fout"
                        )
                    }

                    throw IllegalStateException(
                        "${exception.message ?: "Voucherrequest mislukt."}\n" +
                                "Voucher blijft beschikbaar."
                    )
                }


            val result =
                withContext(
                    Dispatchers.IO
                ) {
                    VoucherResponseHandler
                        .handleAddVoucherResponse(
                            database =
                                database,
                            voucherCode =
                                voucher.code,
                            httpStatus =
                                httpResult.status,
                            responseBody =
                                httpResult.body
                        )
                }


            when (
                result.type
            ) {
                VoucherAddResultType.ADDED -> {
                    appendLog(
                        "Voucher $attempt door NMBS geaccepteerd."
                    )

                    /*
                     * Guard: meteen verifiëren dat er effectief
                     * precies een voucherproduct in het mandje zit.
                     */
                    if (
                        isBasketEmpty()
                    ) {
                        throw IllegalStateException(
                            "NMBS meldde dat de voucher was toegevoegd, " +
                                    "maar het winkelmandje is leeg."
                        )
                    }

                    return ActiveVoucher(
                        code =
                            voucher.code,
                        basketGuid =
                            httpResult.basketGuid
                    )
                }


                VoucherAddResultType.ALREADY_USED -> {
                    excludedCodes.add(
                        voucher.code
                    )

                    appendLog(
                        "Voucher $attempt was al gebruikt → database = USED."
                    )
                }


                VoucherAddResultType.INVALID -> {
                    excludedCodes.add(
                        voucher.code
                    )

                    appendLog(
                        "Voucher $attempt is ongeldig → database = INVALID."
                    )
                }


                VoucherAddResultType.EXPIRED -> {
                    excludedCodes.add(
                        voucher.code
                    )

                    appendLog(
                        "Voucher $attempt is verlopen → database bijgewerkt."
                    )
                }


                VoucherAddResultType.TEMPORARY_ERROR -> {
                    throw IllegalStateException(
                        "Tijdelijke NMBS-fout. Voucher blijft beschikbaar."
                    )
                }


                VoucherAddResultType.UNKNOWN_ERROR -> {
                    throw IllegalStateException(
                        "Onbekend antwoord van NMBS. Voucherstatus werd niet gewijzigd."
                    )
                }
            }
        }
    }


    private suspend fun addVoucher(
        voucherCode: String
    ): VoucherHttpResult {
        evaluateRaw(
            """
            window.__defenseTicketVoucherResult = null;
            """.trimIndent()
        )

        evaluateRaw(
            buildAddVoucherScript(
                voucherCode
            )
        )

        val result =
            waitForJavascriptJson(
                variable =
                    "window.__defenseTicketVoucherResult",
                timeout =
                    REQUEST_TIMEOUT
            )

        if (
            result.optBoolean(
                "scriptError",
                false
            )
        ) {
            throw RequestEngineException(
                errorType =
                    result.optString(
                        "errorType",
                        "SETUP_ERROR"
                    ),
                message =
                    result.optString(
                        "error",
                        "Voucherrequest mislukt."
                    )
            )
        }

        return VoucherHttpResult(
            status =
                result.optInt(
                    "status",
                    -1
                ),
            body =
                result.optString(
                    "body",
                    ""
                ),
            basketGuid =
                result.optString(
                    "basketGuid",
                    ""
                )
        )
    }


    /*
     * ============================================================
     * REQUEST-BASED CHECKOUT
     * ============================================================
     */
    private suspend fun performCheckoutByRequests(
        request: BookingRequest
    ): CheckoutResult {
        evaluateRaw(
            """
            window.__defenseTicketCheckoutResult = null;
            """.trimIndent()
        )

        evaluateRaw(
            buildCheckoutRequestScript(
                request
            )
        )

        val result =
            waitForJavascriptJson(
                variable =
                    "window.__defenseTicketCheckoutResult",
                timeout =
                    REQUEST_TIMEOUT
            )

        if (
            result.optBoolean(
                "scriptError",
                false
            )
        ) {
            throw RequestEngineException(
                errorType =
                    result.optString(
                        "errorType",
                        "CHECKOUT_ERROR"
                    ),
                message =
                    result.optString(
                        "error",
                        "NMBS-checkoutrequest mislukt."
                    )
            )
        }

        return CheckoutResult(
            success =
                result.optBoolean(
                    "success",
                    false
                ),
            finalUrl =
                result.optString(
                    "finalUrl",
                    ""
                ),
            validation =
                result.optString(
                    "validation",
                    ""
                ),
            title =
                result.optString(
                    "title",
                    ""
                )
        )
    }


    /*
     * ============================================================
     * BASKET GUARDS / CLEANUP
     * ============================================================
     */

    private suspend fun ensureBasketCleanBeforeTrip() {
        val empty =
            isBasketEmpty()

        if (
            empty
        ) {
            return
        }

        appendLog(
            "Oud NMBS-winkelmandje gevonden → wordt leeggemaakt."
        )

        val cleaned =
            emptyBasketAndVerify()

        if (
            !cleaned
        ) {
            throw IllegalStateException(
                "Er staat nog een oud voucher-item in het NMBS-winkelmandje " +
                        "en dit kon niet veilig worden verwijderd. De boeking is gestopt " +
                        "om dubbele tickets te voorkomen."
            )
        }

        appendLog(
            "Oud winkelmandje leeggemaakt."
        )
    }


    private suspend fun cleanupFailedVoucher(
        activeVoucher: ActiveVoucher
    ) {
        val removed =
            if (
                activeVoucher.basketGuid.isNotBlank()
            ) {
                removeVoucherItem(
                    activeVoucher
                )
            } else {
                false
            }

        if (
            !removed
        ) {
            /*
             * Fallback. Er hoort door onze basket guard maar één
             * voucher-item aanwezig te zijn.
             */
            emptyBasket()
        }

        val empty =
            waitForBasketEmpty()

        if (
            empty
        ) {
            appendLog(
                "Mislukte checkout opgeruimd; voucher blijft beschikbaar."
            )
        } else {
            appendLog(
                "WAARSCHUWING: NMBS-winkelmandje kon niet volledig worden opgeruimd."
            )
        }
    }


    private suspend fun removeVoucherItem(
        activeVoucher: ActiveVoucher
    ): Boolean {
        evaluateRaw(
            """
            window.__defenseTicketRemoveResult = null;
            """.trimIndent()
        )

        evaluateRaw(
            buildRemoveVoucherScript(
                activeVoucher
            )
        )

        val result =
            try {
                waitForJavascriptJson(
                    variable =
                        "window.__defenseTicketRemoveResult",
                    timeout =
                        BASKET_ACTION_TIMEOUT
                )
            } catch (
                _: Exception
            ) {
                return false
            }

        return result.optBoolean(
            "ok",
            false
        )
    }


    private suspend fun emptyBasketAndVerify(): Boolean =
        emptyBasket() &&
                waitForBasketEmpty()


    private suspend fun emptyBasket(): Boolean {
        evaluateRaw(
            """
            window.__defenseTicketEmptyBasketResult = null;

            (async () => {

                try {

                    const response =
                        await fetch(
                            "/api/Basket/EmptyTheBasket",
                            {
                                method:
                                    "GET",

                                credentials:
                                    "include",

                                cache:
                                    "no-store"
                            }
                        );


                    window.__defenseTicketEmptyBasketResult =
                        JSON.stringify(
                            {
                                ok:
                                    response.ok,

                                status:
                                    response.status
                            }
                        );

                } catch (error) {

                    window.__defenseTicketEmptyBasketResult =
                        JSON.stringify(
                            {
                                ok:
                                    false,

                                status:
                                    -1
                            }
                        );
                }

            })();

            "STARTED";
            """.trimIndent()
        )

        val result =
            try {
                waitForJavascriptJson(
                    variable =
                        "window.__defenseTicketEmptyBasketResult",
                    timeout =
                        BASKET_ACTION_TIMEOUT
                )
            } catch (
                _: Exception
            ) {
                return false
            }

        return result.optBoolean(
            "ok",
            false
        )
    }


    private suspend fun waitForBasketEmpty(): Boolean {
        repeat(
            20
        ) {
            val empty =
                isBasketEmpty()

            if (
                empty
            ) {
                return true
            }

            delay(
                500.milliseconds
            )
        }

        return false
    }


    private suspend fun isBasketEmpty(): Boolean {
        evaluateRaw(
            """
            window.__defenseTicketBasketResult = null;

            (async () => {

                try {

                    const response =
                        await fetch(
                            "/api/Basket/IsProductInBasketAndVoucherIsAdded",
                            {
                                method:
                                    "GET",

                                credentials:
                                    "include",

                                cache:
                                    "no-store"
                            }
                        );


                    const body =
                        (
                            await response.text()
                        )
                            .trim()
                            .toLowerCase();


                    window.__defenseTicketBasketResult =
                        JSON.stringify(
                            {
                                ok:
                                    response.ok,

                                body:
                                    body
                            }
                        );

                } catch (error) {

                    window.__defenseTicketBasketResult =
                        JSON.stringify(
                            {
                                ok:
                                    false,

                                body:
                                    ""
                            }
                        );
                }

            })();

            "STARTED";
            """.trimIndent()
        )

        val result =
            try {
                waitForJavascriptJson(
                    variable =
                        "window.__defenseTicketBasketResult",
                    timeout =
                        BASKET_STATUS_TIMEOUT
                )
            } catch (
                _: Exception
            ) {
                throw IllegalStateException(
                    "NMBS-winkelmandje kon niet worden gecontroleerd."
                )
            }

        if (
            !result.optBoolean(
                "ok",
                false
            )
        ) {
            throw IllegalStateException(
                "NMBS-winkelmandje kon niet worden gecontroleerd."
            )
        }

        /*
         * Endpoint:
         * true  = voucherproduct aanwezig
         * false = geen voucherproduct aanwezig
         */
        return result.optString(
            "body"
        ) == "false"
    }


    /*
     * ============================================================
     * BROWSER CHECK
     * ============================================================
     */
    private suspend fun handleBrowserCheck(
        resumePhase: BookingPhase
    ) {
        if (
            !isBrowserCheckPage()
        ) {
            return
        }

        publish(
            phase =
                BookingPhase.NEEDS_INTERACTION,
            message =
                "NMBS vraagt een browsercontrole. Rond die af op het scherm.",
            running =
                true,
            interaction =
                true
        )

        appendLog(
            "NMBS-browsercontrole vereist."
        )

        withTimeout(
            BROWSER_CHECK_TIMEOUT
        ) {
            while (
                isBrowserCheckPage()
            ) {
                delay(
                    1.seconds
                )
            }
        }

        publish(
            phase =
                resumePhase,
            message =
                "Browsercontrole voltooid.",
            running =
                true,
            interaction =
                false
        )

        appendLog(
            "Browsercontrole voltooid."
        )

        delay(
            1.seconds
        )
    }


    private suspend fun isBrowserCheckPage(): Boolean {
        return try {
            evaluateBoolean(
                """
                (() => {

                    const title =
                        (
                            document.title || ""
                        )
                            .toLowerCase();


                    const body =
                        (
                            document.body?.innerText || ""
                        )
                            .toLowerCase()
                            .slice(
                                0,
                                5000
                            );


                    return (
                        title.includes(
                            "just a moment"
                        ) ||
                        body.includes(
                            "checking your browser"
                        ) ||
                        body.includes(
                            "verify you are human"
                        ) ||
                        body.includes(
                            "controleren of je een mens bent"
                        ) ||
                        !!document.querySelector(
                            "#challenge-running"
                        ) ||
                        !!document.querySelector(
                            ".cf-challenge"
                        ) ||
                        !!document.querySelector(
                            ".cf-turnstile"
                        )
                    );

                })()
                """.trimIndent()
            )

        } catch (
            _: Exception
        ) {
            false
        }
    }


    /*
     * ============================================================
     * PAGE / JS HELPERS
     * ============================================================
     */
    private suspend fun loadPage(
        url: String,
        expectedUrl: (String) -> Boolean
    ) {
        val sequenceBefore =
            withContext(
                Dispatchers.Main
            ) {
                val current =
                    pageSequence

                requireWebView()
                    .loadUrl(
                        url
                    )

                current
            }

        withTimeout(
            PAGE_TIMEOUT
        ) {
            pageEvents.first {
                it.sequence >
                        sequenceBefore &&
                        expectedUrl(
                            it.url
                        )
            }
        }
    }


    private suspend fun waitForDefenseTicket(): Boolean =
        withTimeoutOrNull(
            PRODUCT_TIMEOUT
        ) {
            while (
                !evaluateBoolean(
                    """
                    (() => {

                        return (
                            !!document.querySelector(
                                '.js-addToBasket[data-product-id="ML0"]'
                            ) ||
                            !!document.querySelector(
                                '[data-product-id="ML0"]'
                            ) ||
                            !!document.querySelector(
                                '#VoucherCode-ML0'
                            )
                        );

                    })()
                    """.trimIndent()
                )
            ) {
                delay(
                    500.milliseconds
                )
            }

            true
        } ?: false


    private suspend fun evaluateRaw(
        javascript: String
    ): String? {
        return withContext(
            Dispatchers.Main
        ) {
            val view =
                requireWebView()

            suspendCancellableCoroutine { continuation ->

                view.evaluateJavascript(
                    javascript
                ) { result ->

                    if (
                        continuation.isActive
                    ) {
                        continuation.resume(
                            result
                        )
                    }
                }
            }
        }
    }


    private suspend fun evaluateString(
        javascript: String
    ): String? {
        val raw =
            evaluateRaw(
                javascript
            )
                ?: return null

        if (
            raw == "null" ||
            raw == "undefined"
        ) {
            return null
        }

        return try {
            val array =
                JSONArray(
                    "[$raw]"
                )

            when {
                array.isNull(
                    0
                ) ->
                    null

                else ->
                    array.getString(
                        0
                    )
            }

        } catch (
            _: Exception
        ) {
            raw.removeSurrounding(
                "\""
            )
        }
    }


    private suspend fun evaluateBoolean(
        javascript: String
    ): Boolean {
        return evaluateRaw(
            javascript
        ) == "true"
    }


    private suspend fun waitForJavascriptJson(
        variable: String,
        timeout: Duration
    ): JSONObject {
        return withTimeout(
            timeout
        ) {
            var value: String? =
                null

            while (
                value.isNullOrBlank()
            ) {
                value =
                    evaluateString(
                        variable
                    )

                if (
                    value.isNullOrBlank()
                ) {
                    delay(
                        200.milliseconds
                    )
                }
            }

            JSONObject(
                requireNotNull(
                    value
                )
            )
        }
    }


    private suspend fun awaitWebView() {
        val attached =
            withTimeoutOrNull(
                WEBVIEW_ATTACH_TIMEOUT
            ) {
                while (
                    webView == null
                ) {
                    delay(
                        100.milliseconds
                    )
                }

                true
            } ?: false

        if (
            !attached
        ) {
            throw IllegalStateException(
                "NMBS-browserengine kon niet worden gestart."
            )
        }
    }


    private fun requireWebView(): WebView {
        return webView
            ?: throw IllegalStateException(
                "NMBS WebView is niet gekoppeld."
            )
    }


    /*
     * ============================================================
     * JAVASCRIPT: STATION RESOLVER
     * ============================================================
     */
    private fun buildStationResolveScript(
        departure: String,
        arrival: String
    ): String {
        val from =
            JSONObject.quote(
                departure
            )

        val to =
            JSONObject.quote(
                arrival
            )

        return """
            (() => {

                window.__defenseTicketStationResult =
                    null;


                const FROM =
                    $from;

                const TO =
                    $to;


                const sleep =
                    ms =>
                        new Promise(
                            resolve =>
                                setTimeout(
                                    resolve,
                                    ms
                                )
                        );


                const cleanText =
                    value =>
                        (
                            value || ""
                        )
                            .toLowerCase()
                            .normalize(
                                "NFD"
                            )
                            .replace(
                                /[\u0300-\u036f]/g,
                                ""
                            )
                            .replace(
                                /\s+/g,
                                " "
                            )
                            .trim();


                const normalize =
                    value =>
                        cleanText(
                            value
                        )
                            .replace(
                                /[^a-z0-9]+/g,
                                ""
                            );


                const visible =
                    element => {

                        if (!element) {
                            return false;
                        }


                        const style =
                            getComputedStyle(
                                element
                            );


                        const rect =
                            element.getBoundingClientRect();


                        return (
                            style.display !== "none" &&
                            style.visibility !== "hidden" &&
                            rect.width > 0 &&
                            rect.height > 0
                        );
                    };


                const setValue =
                    (
                        input,
                        value
                    ) => {

                        const descriptor =
                            Object.getOwnPropertyDescriptor(
                                HTMLInputElement.prototype,
                                "value"
                            );


                        if (
                            descriptor?.set
                        ) {

                            descriptor.set.call(
                                input,
                                value
                            );

                        } else {

                            input.value =
                                value;
                        }


                        input.dispatchEvent(
                            new Event(
                                "input",
                                {
                                    bubbles:
                                        true
                                }
                            )
                        );


                        input.dispatchEvent(
                            new Event(
                                "change",
                                {
                                    bubbles:
                                        true
                                }
                            )
                        );
                    };


                const stationInputs =
                    () => {

                        const inputs =
                            Array.from(
                                document.querySelectorAll(
                                    'input:not([type="hidden"])'
                                )
                            )
                                .filter(
                                    visible
                                );


                        let candidates =
                            inputs.filter(
                                input => {

                                    const placeholder =
                                        cleanText(
                                            input.placeholder
                                        );


                                    return (
                                        placeholder.includes(
                                            "station"
                                        ) ||
                                        placeholder.includes(
                                            "halte"
                                        )
                                    );
                                }
                            );


                        if (
                            candidates.length >= 2
                        ) {

                            return candidates;
                        }


                        return inputs.filter(
                            input => {

                                const identity =
                                    normalize(
                                        (
                                            input.name || ""
                                        ) +
                                        " " +
                                        (
                                            input.id || ""
                                        )
                                    );


                                return (
                                    identity.includes(
                                        "origin"
                                    ) ||
                                    identity.includes(
                                        "departure"
                                    ) ||
                                    identity.includes(
                                        "destination"
                                    ) ||
                                    identity.includes(
                                        "arrival"
                                    )
                                );
                            }
                        );
                    };


                const selectStation =
                    async (
                        input,
                        stationName
                    ) => {

                        input.focus();


                        setValue(
                            input,
                            ""
                        );


                        await sleep(
                            200
                        );


                        setValue(
                            input,
                            stationName
                        );


                        input.dispatchEvent(
                            new KeyboardEvent(
                                "keyup",
                                {
                                    key:
                                        stationName.slice(
                                            -1
                                        ),

                                    bubbles:
                                        true
                                }
                            )
                        );


                        await sleep(
                            1800
                        );


                        const wanted =
                            cleanText(
                                stationName
                            );


                        const suggestions =
                            Array.from(
                                document.querySelectorAll(
                                    [
                                        "[role='option']",
                                        "[role='listbox'] li",
                                        ".autocomplete-suggestion",
                                        ".tt-suggestion",
                                        "[class*='suggestion']",
                                        "[class*='autocomplete'] li",
                                        "ul li"
                                    ].join(
                                        ","
                                    )
                                )
                            )
                                .filter(
                                    visible
                                )
                                .filter(
                                    element => {

                                        const value =
                                            cleanText(
                                                element.innerText ||
                                                element.textContent
                                            );


                                        return (
                                            value === wanted ||
                                            value.startsWith(
                                                wanted + " "
                                            ) ||
                                            value.includes(
                                                wanted
                                            )
                                        );
                                    }
                                );


                        if (
                            suggestions.length > 0
                        ) {

                            const target =
                                suggestions[0]
                                    .closest(
                                        "[role='option'], li, button, a"
                                    ) ||
                                suggestions[0];


                            target.click();


                            await sleep(
                                900
                            );


                            return;
                        }


                        /*
                         * Fallback:
                         * eerste suggestie via toetsenbord.
                         */
                        input.dispatchEvent(
                            new KeyboardEvent(
                                "keydown",
                                {
                                    key:
                                        "ArrowDown",

                                    code:
                                        "ArrowDown",

                                    keyCode:
                                        40,

                                    which:
                                        40,

                                    bubbles:
                                        true
                                }
                            )
                        );


                        await sleep(
                            250
                        );


                        input.dispatchEvent(
                            new KeyboardEvent(
                                "keydown",
                                {
                                    key:
                                        "Enter",

                                    code:
                                        "Enter",

                                    keyCode:
                                        13,

                                    which:
                                        13,

                                    bubbles:
                                        true
                                }
                            )
                        );


                        await sleep(
                            900
                        );
                    };


                const numeric =
                    value => {

                        const result =
                            String(
                                value || ""
                            )
                                .trim();


                        return /^[0-9]+$/.test(
                            result
                        )
                            ? result
                            : null;
                    };


                const findReference =
                    mode => {

                        const fields =
                            Array.from(
                                document.querySelectorAll(
                                    "input"
                                )
                            );


                        let best =
                            null;

                        let bestScore =
                            0;


                        for (
                            const field
                            of fields
                        ) {

                            const value =
                                numeric(
                                    field.value
                                );


                            if (!value) {
                                continue;
                            }


                            const identity =
                                normalize(
                                    (
                                        field.name || ""
                                    ) +
                                    " " +
                                    (
                                        field.id || ""
                                    )
                                );


                            let score =
                                0;


                            if (
                                identity.includes(
                                    "stationreference"
                                )
                            ) {

                                score +=
                                    20;
                            }


                            if (
                                identity.includes(
                                    "reference"
                                )
                            ) {

                                score +=
                                    8;
                            }


                            if (
                                identity.includes(
                                    "station"
                                )
                            ) {

                                score +=
                                    5;
                            }


                            if (
                                mode ===
                                    "departure"
                            ) {

                                if (
                                    identity.includes(
                                        "departure"
                                    )
                                ) {

                                    score +=
                                        30;
                                }


                                if (
                                    identity.includes(
                                        "origin"
                                    )
                                ) {

                                    score +=
                                        30;
                                }
                            }


                            if (
                                mode ===
                                    "arrival"
                            ) {

                                if (
                                    identity.includes(
                                        "arrival"
                                    )
                                ) {

                                    score +=
                                        30;
                                }


                                if (
                                    identity.includes(
                                        "destination"
                                    )
                                ) {

                                    score +=
                                        30;
                                }
                            }


                            if (
                                score >
                                bestScore
                            ) {

                                bestScore =
                                    score;

                                best =
                                    value;
                            }
                        }


                        return best;
                    };


                (
                    async () => {

                        try {

                            const inputs =
                                stationInputs();


                            if (
                                inputs.length < 2
                            ) {

                                throw new Error(
                                    "Van/Naar-stationsvelden werden niet gevonden."
                                );
                            }


                            await selectStation(
                                inputs[0],
                                FROM
                            );


                            await selectStation(
                                inputs[1],
                                TO
                            );


                            await sleep(
                                600
                            );


                            const departureReference =
                                findReference(
                                    "departure"
                                );


                            const arrivalReference =
                                findReference(
                                    "arrival"
                                );


                            if (
                                !departureReference ||
                                !arrivalReference
                            ) {

                                throw new Error(
                                    "NMBS-stationcodes konden niet worden bepaald."
                                );
                            }


                            window.__defenseTicketStationResult =
                                JSON.stringify(
                                    {
                                        error:
                                            false,

                                        departureReference:
                                            departureReference,

                                        arrivalReference:
                                            arrivalReference
                                    }
                                );

                        } catch (
                            error
                        ) {

                            window.__defenseTicketStationResult =
                                JSON.stringify(
                                    {
                                        error:
                                            true,

                                        message:
                                            String(
                                                error?.message ||
                                                error
                                            )
                                    }
                                );
                        }

                    }
                )();


                return "STARTED";

            })()
        """.trimIndent()
    }


    /*
     * ============================================================
     * JAVASCRIPT: ADD VOUCHER REQUEST
     * ============================================================
     */
    private fun buildAddVoucherScript(
        voucherCode: String
    ): String {
        val voucher =
            JSONObject.quote(
                voucherCode
            )

        return """
            (() => {

                window.__defenseTicketVoucherResult =
                    null;


                const VOUCHER =
                    $voucher;


                const finishError =
                    (
                        errorType,
                        message
                    ) => {

                        window.__defenseTicketVoucherResult =
                            JSON.stringify(
                                {
                                    scriptError:
                                        true,

                                    errorType:
                                        errorType,

                                    error:
                                        String(
                                            message ||
                                            "Onbekende fout"
                                        )
                                }
                            );
                    };


                (
                    async () => {

                        try {

                            const button =
                                document.querySelector(
                                    '.js-addToBasket[data-product-id="ML0"]'
                                );


                            if (!button) {

                                finishError(
                                    "SETUP_ERROR",
                                    "ML0 AddToBasket-knop werd niet gevonden."
                                );

                                return;
                            }


                            const get =
                                (...names) => {

                                    for (
                                        const name
                                        of names
                                    ) {

                                        const value =
                                            button.getAttribute(
                                                name
                                            );


                                        if (
                                            value !== null &&
                                            value !== ""
                                        ) {

                                            return value;
                                        }
                                    }


                                    return "";
                                };


                            const ticketGuid =
                                get(
                                    "data-ticket-guid"
                                );


                            const origin =
                                get(
                                    "data-departure-station-id",
                                    "data-origin-id"
                                );


                            const destination =
                                get(
                                    "data-destination-station-id",
                                    "data-arrival-station-id"
                                );


                            const departureDate =
                                get(
                                    "data-departure-date"
                                );


                            if (
                                !ticketGuid ||
                                !origin ||
                                !destination ||
                                !departureDate
                            ) {

                                finishError(
                                    "SETUP_ERROR",
                                    "Benodigde ML0-productdata ontbreekt."
                                );

                                return;
                            }


                            const params =
                                new URLSearchParams();


                            params.set(
                                "item[id]",
                                "ML0"
                            );


                            params.set(
                                "item[producttype]",
                                get(
                                    "data-product-type"
                                ) ||
                                "6"
                            );


                            params.set(
                                "item[datasourceticketguid]",
                                ticketGuid
                            );


                            params.set(
                                "item[pricecategory]",
                                get(
                                    "data-price-category"
                                ) ||
                                "1"
                            );


                            params.set(
                                "item[productprice]",
                                get(
                                    "data-price",
                                    "data-product-price"
                                ) ||
                                "0.00"
                            );


                            params.set(
                                "item[numberoftickets]",
                                "1"
                            );


                            params.set(
                                "item[originid]",
                                origin
                            );


                            params.set(
                                "item[destinationid]",
                                destination
                            );


                            params.set(
                                "item[travelclass]",
                                get(
                                    "data-travel-class"
                                ) ||
                                "2"
                            );


                            params.set(
                                "item[journeytype]",
                                get(
                                    "data-journey-type"
                                ) ||
                                "OneWayTrip"
                            );


                            params.set(
                                "item[departuredate]",
                                departureDate
                            );


                            params.set(
                                "item[returndate]",
                                get(
                                    "data-return-date"
                                )
                            );


                            params.set(
                                "item[enddate]",
                                get(
                                    "data-end-date"
                                )
                            );


                            params.set(
                                "item[showinfoalerttothesecondtraveler]",
                                "False"
                            );


                            params.set(
                                "item[newvouchercode]",
                                VOUCHER
                            );


                            const popupFallbacks = {

                                "popups[thresholdExceededPopupDs]":
                                    "{9B6AD4E4-2762-46F7-9E21-4CE10E80C73A}",

                                "popups[unitPriceChangedPopupDs]":
                                    "{85563F56-4FA4-4D7D-9E83-1608EEBC1978}",

                                "popups[goUnlimitedThresholdExceededPopupDataSourceDs]":
                                    "{39E3D9BD-D6FB-4B9A-A5C7-20AD73F4B84D}",

                                "popups[subscriptionRemovedOnCheckoutPagePopupDs]":
                                    "{A2704FF9-4A00-416B-990A-21F5D31DE502}",

                                "popups[subscriptionRemovedOnMobibCardSelectionPagePopupDs]":
                                    "{3610FC86-94C0-45D2-8EC1-C1FAFA801BBE}"
                            };


                            Object.entries(
                                popupFallbacks
                            )
                                .forEach(
                                    ([key, value]) =>
                                        params.set(
                                            key,
                                            value
                                        )
                                );


                            let response;


                            try {

                                response =
                                    await fetch(
                                        "/api/basket/AddItemToBasket/",
                                        {
                                            method:
                                                "POST",

                                            credentials:
                                                "include",

                                            headers: {

                                                "Content-Type":
                                                    "application/x-www-form-urlencoded; charset=UTF-8",

                                                "X-Requested-With":
                                                    "XMLHttpRequest"
                                            },

                                            body:
                                                params.toString()
                                        }
                                    );

                            } catch (
                                error
                            ) {

                                finishError(
                                    "NETWORK_ERROR",
                                    "AddItemToBasket fetch() faalde: " +
                                    String(
                                        error?.message ||
                                        error
                                    )
                                );

                                return;
                            }


                            const body =
                                await response.text();


                            /*
                             * Bij HTTP 200 bevat BasketComponent de
                             * bijgewerkte basket HTML. Daaruit halen we
                             * de data-guid van precies deze voucher.
                             *
                             * Als NMBS de markup wijzigt en geen GUID
                             * gevonden wordt, blijft basketGuid leeg en
                             * gebruiken we bij cleanup een veilige
                             * EmptyTheBasket fallback.
                             */
                            let basketGuid =
                                "";


                            try {

                                if (
                                    response.ok
                                ) {

                                    const parsed =
                                        JSON.parse(
                                            body
                                        );


                                    const basketHtml =
                                        parsed.BasketComponent ||
                                        "";


                                    if (
                                        basketHtml
                                    ) {

                                        const basketDoc =
                                            new DOMParser()
                                                .parseFromString(
                                                    basketHtml,
                                                    "text/html"
                                                );


                                        const voucherElements =
                                            Array.from(
                                                basketDoc.querySelectorAll(
                                                    "[data-guid][data-vouchercode]"
                                                )
                                            );


                                        const exact =
                                            voucherElements.find(
                                                element =>
                                                    String(
                                                        element.getAttribute(
                                                            "data-vouchercode"
                                                        ) ||
                                                        ""
                                                    )
                                                        .trim() ===
                                                    VOUCHER
                                            );


                                        if (
                                            exact
                                        ) {

                                            basketGuid =
                                                exact.getAttribute(
                                                    "data-guid"
                                                ) ||
                                                "";
                                        }
                                    }
                                }

                            } catch (_) {
                            }


                            window.__defenseTicketVoucherResult =
                                JSON.stringify(
                                    {
                                        scriptError:
                                            false,

                                        status:
                                            response.status,

                                        body:
                                            body.slice(
                                                0,
                                                6000
                                            ),

                                        basketGuid:
                                            basketGuid
                                    }
                                );

                        } catch (
                            error
                        ) {

                            finishError(
                                "SETUP_ERROR",
                                String(
                                    error?.message ||
                                    error
                                )
                            );
                        }

                    }
                )();


                return "STARTED";

            })()
        """.trimIndent()
    }


    /*
     * ============================================================
     * JAVASCRIPT: REMOVE EXACT VOUCHER ITEM
     * ============================================================
     */
    private fun buildRemoveVoucherScript(
        activeVoucher: ActiveVoucher
    ): String {
        val code =
            JSONObject.quote(
                activeVoucher.code
            )

        val guid =
            JSONObject.quote(
                activeVoucher.basketGuid
            )

        return """
            (() => {

                window.__defenseTicketRemoveResult =
                    null;


                const VOUCHER =
                    $code;

                const GUID =
                    $guid;


                (async () => {

                    try {

                        const params =
                            new URLSearchParams();


                        params.set(
                            "guid",
                            GUID
                        );


                        params.set(
                            "vouchercode",
                            VOUCHER
                        );


                        params.set(
                            "popups[thresholdExceededPopupDs]",
                            "{9B6AD4E4-2762-46F7-9E21-4CE10E80C73A}"
                        );


                        params.set(
                            "popups[unitPriceChangedPopupDs]",
                            "{85563F56-4FA4-4D7D-9E83-1608EEBC1978}"
                        );


                        params.set(
                            "popups[goUnlimitedThresholdExceededPopupDataSourceDs]",
                            "{39E3D9BD-D6FB-4B9A-A5C7-20AD73F4B84D}"
                        );


                        params.set(
                            "popups[subscriptionRemovedOnCheckoutPagePopupDs]",
                            "{A2704FF9-4A00-416B-990A-21F5D31DE502}"
                        );


                        params.set(
                            "popups[subscriptionRemovedOnMobibCardSelectionPagePopupDs]",
                            "{3610FC86-94C0-45D2-8EC1-C1FAFA801BBE}"
                        );


                        const response =
                            await fetch(
                                "/api/basket/RemoveItemFromBasket/",
                                {
                                    method:
                                        "POST",

                                    credentials:
                                        "include",

                                    headers: {

                                        "Content-Type":
                                            "application/x-www-form-urlencoded; charset=UTF-8",

                                        "X-Requested-With":
                                            "XMLHttpRequest"
                                    },

                                    body:
                                        params.toString()
                                }
                            );


                        window.__defenseTicketRemoveResult =
                            JSON.stringify(
                                {
                                    ok:
                                        response.ok,

                                    status:
                                        response.status
                                }
                            );

                    } catch (
                        error
                    ) {

                        window.__defenseTicketRemoveResult =
                            JSON.stringify(
                                {
                                    ok:
                                        false,

                                    status:
                                        -1
                                }
                            );
                    }

                })();


                return "STARTED";

            })()
        """.trimIndent()
    }


    /*
     * ============================================================
     * JAVASCRIPT: GET ORDER FORM + POST ORDER FORM
     * ============================================================
     */
    private fun buildCheckoutRequestScript(
        request: BookingRequest
    ): String {
        val firstName =
            JSONObject.quote(
                request.firstName
            )

        val lastName =
            JSONObject.quote(
                request.lastName
            )

        val email =
            JSONObject.quote(
                request.email
            )

        return """
            (() => {

                window.__defenseTicketCheckoutResult =
                    null;


                const FIRST_NAME =
                    $firstName;

                const LAST_NAME =
                    $lastName;

                const EMAIL =
                    $email;


                const finishError =
                    (
                        errorType,
                        message
                    ) => {

                        window.__defenseTicketCheckoutResult =
                            JSON.stringify(
                                {
                                    scriptError:
                                        true,

                                    errorType:
                                        errorType,

                                    error:
                                        String(
                                            message ||
                                            "Onbekende checkoutfout"
                                        )
                                }
                            );
                    };


                const addFormControls =
                    (
                        form,
                        params
                    ) => {

                        const controls =
                            Array.from(
                                form.elements ||
                                []
                            );


                        for (
                            const control
                            of controls
                        ) {

                            const name =
                                control.name;


                            if (!name) {
                                continue;
                            }


                            if (
                                control.disabled
                            ) {
                                continue;
                            }


                            if (
                                (
                                    control.type ===
                                    "checkbox" ||
                                    control.type ===
                                    "radio"
                                ) &&
                                !control.checked
                            ) {
                                continue;
                            }


                            if (
                                control instanceof
                                HTMLSelectElement
                            ) {

                                Array.from(
                                    control.options
                                )
                                    .filter(
                                        option =>
                                            option.selected
                                    )
                                    .forEach(
                                        option =>
                                            params.append(
                                                name,
                                                option.value
                                            )
                                    );

                                continue;
                            }


                            params.append(
                                name,
                                control.value ||
                                ""
                            );
                        }
                    };


                (
                    async () => {

                        try {

                            /*
                             * =================================
                             * GET /nl/order-form
                             * =================================
                             */

                            let getResponse;


                            try {

                                getResponse =
                                    await fetch(
                                        "/nl/order-form",
                                        {
                                            method:
                                                "GET",

                                            credentials:
                                                "include",

                                            redirect:
                                                "follow"
                                        }
                                    );

                            } catch (
                                error
                            ) {

                                finishError(
                                    "NETWORK_ERROR",
                                    "GET /nl/order-form faalde: " +
                                    String(
                                        error?.message ||
                                        error
                                    )
                                );

                                return;
                            }


                            const checkoutHtml =
                                await getResponse.text();


                            const checkoutDoc =
                                new DOMParser()
                                    .parseFromString(
                                        checkoutHtml,
                                        "text/html"
                                    );


                            const form =
                                checkoutDoc.querySelector(
                                    "#form_Checkout"
                                );


                            if (!form) {

                                finishError(
                                    "CHECKOUT_ERROR",
                                    "form_Checkout werd niet gevonden in GET /nl/order-form."
                                );

                                return;
                            }


                            /*
                             * Alle bestaande hidden/default
                             * controls meenemen.
                             *
                             * Zo behouden we token, GUID, enz.
                             */
                            const params =
                                new URLSearchParams();


                            addFormControls(
                                form,
                                params
                            );


                            /*
                             * =================================
                             * OVERRIDES
                             * =================================
                             */

                            params.set(
                                "Basket.Customer.FirstName",
                                FIRST_NAME
                            );


                            params.set(
                                "Basket.Customer.LastName",
                                LAST_NAME
                            );


                            params.set(
                                "Basket.Customer.Email",
                                EMAIL
                            );


                            /*
                             * Alle reizigersnaam-fields uit het
                             * echte formulier bepalen.
                             */
                            Array.from(
                                form.querySelectorAll(
                                    '[name*=".Travellers["][name$=".FirstName"]'
                                )
                            )
                                .forEach(
                                    element =>
                                        params.set(
                                            element.name,
                                            FIRST_NAME
                                        )
                                );


                            Array.from(
                                form.querySelectorAll(
                                    '[name*=".Travellers["][name$=".LastName"]'
                                )
                            )
                                .forEach(
                                    element =>
                                        params.set(
                                            element.name,
                                            LAST_NAME
                                        )
                                );


                            Array.from(
                                form.querySelectorAll(
                                    '[name$=".MobibNumber"]'
                                )
                            )
                                .forEach(
                                    element =>
                                        params.set(
                                            element.name,
                                            ""
                                        )
                                );


                            Array.from(
                                form.querySelectorAll(
                                    '[name$=".IsUniqueTraveller"]'
                                )
                            )
                                .forEach(
                                    element =>
                                        params.set(
                                            element.name,
                                            "false"
                                        )
                                );


                            params.set(
                                "Basket.DeliveryMethod",
                                "1"
                            );


                            /*
                             * De gebruiker heeft dit expliciet in
                             * onze Compose UI aangevinkt.
                             */
                            params.set(
                                "AcceptTermsConditions",
                                "true"
                            );


                            const token =
                                params.get(
                                    "__RequestVerificationToken"
                                );


                            if (!token) {

                                finishError(
                                    "CHECKOUT_ERROR",
                                    "Anti-forgery token ontbreekt in order-form."
                                );

                                return;
                            }


                            const ticketGuidKeys =
                                Array.from(
                                    params.keys()
                                )
                                    .filter(
                                        key =>
                                            /\.Guid$/.test(
                                                key
                                            ) &&
                                            key.includes(
                                                "TicketItems"
                                            )
                                    );


                            if (
                                ticketGuidKeys.length === 0
                            ) {

                                finishError(
                                    "CHECKOUT_ERROR",
                                    "Basket TicketItems GUID ontbreekt in order-form."
                                );

                                return;
                            }


                            /*
                             * VEILIGHEIDSGUARD:
                             *
                             * Deze app boekt per bestelling exact één
                             * enkele rit met exact één voucher.
                             *
                             * Meer dan één TicketItem betekent dat er
                             * nog een oud item in het NMBS-mandje zit.
                             * Dan NIET submitten.
                             */
                            if (
                                ticketGuidKeys.length !== 1
                            ) {

                                finishError(
                                    "BASKET_GUARD",
                                    "NMBS-winkelmand bevat " +
                                    ticketGuidKeys.length +
                                    " ticketitems. Verwacht exact 1; checkout geannuleerd."
                                );

                                return;
                            }


                            /*
                             * =================================
                             * POST /nl/order-form
                             * =================================
                             */

                            const action =
                                form.getAttribute(
                                    "action"
                                ) ||
                                "/nl/order-form";


                            const postUrl =
                                new URL(
                                    action,
                                    location.origin
                                ).toString();


                            let postResponse;


                            try {

                                postResponse =
                                    await fetch(
                                        postUrl,
                                        {
                                            method:
                                                "POST",

                                            credentials:
                                                "include",

                                            redirect:
                                                "follow",

                                            headers: {

                                                "Content-Type":
                                                    "application/x-www-form-urlencoded; charset=UTF-8"
                                            },

                                            body:
                                                params.toString()
                                        }
                                    );

                            } catch (
                                error
                            ) {

                                finishError(
                                    "CHECKOUT_POST_STATUS_UNKNOWN",
                                    "De verbinding viel weg tijdens POST /nl/order-form: " +
                                    String(
                                        error?.message ||
                                        error
                                    )
                                );

                                return;
                            }


                            const finalUrl =
                                postResponse.url ||
                                "";


                            const finalHtml =
                                await postResponse.text();


                            const finalDoc =
                                new DOMParser()
                                    .parseFromString(
                                        finalHtml,
                                        "text/html"
                                    );


                            const finalUrlObject =
                                new URL(
                                    finalUrl ||
                                    location.href
                                );


                            const amount =
                                finalUrlObject
                                    .searchParams
                                    .get(
                                        "amount"
                                    );


                            const successUrl =
                                finalUrlObject
                                    .pathname
                                    .includes(
                                        "/success"
                                    ) &&

                                finalUrlObject
                                    .searchParams
                                    .get(
                                        "payment_success"
                                    ) === "1" &&

                                (
                                    amount === "0" ||
                                    amount === "0.00"
                                ) &&

                                (
                                    finalUrlObject
                                        .searchParams
                                        .get(
                                            "deliverymethod"
                                        ) === "1" ||

                                    finalUrlObject
                                        .searchParams
                                        .get(
                                            "deliverymethod"
                                        ) === null
                                );


                            const successMarker =
                                !!finalDoc.querySelector(
                                    ".gtm-purchase-completed"
                                ) ||

                                (
                                    finalDoc.title ||
                                    ""
                                )
                                    .toLowerCase()
                                    .includes(
                                        "bestelling succesvol"
                                    );


                            /*
                             * De door NMBS zelf gegeven success-URL is
                             * de primaire bevestiging. De HTML-marker
                             * bewaren we als extra signaal.
                             */
                            const success =
                                successUrl &&
                                (
                                    successMarker ||
                                    postResponse.ok
                                );


                            const validation =
                                Array.from(
                                    finalDoc.querySelectorAll(
                                        [
                                            ".field-validation-error",
                                            ".validation-summary-errors",
                                            "[class*='validation-error']",
                                            "[class*='error-message']",
                                            ".error"
                                        ].join(
                                            ","
                                        )
                                    )
                                )
                                    .map(
                                        element =>
                                            (
                                                element.innerText ||
                                                element.textContent ||
                                                ""
                                            )
                                                .trim()
                                    )
                                    .filter(
                                        Boolean
                                    )
                                    .join(
                                        " | "
                                    )
                                    .slice(
                                        0,
                                        800
                                    );


                            window.__defenseTicketCheckoutResult =
                                JSON.stringify(
                                    {
                                        scriptError:
                                            false,

                                        success:
                                            success,

                                        finalUrl:
                                            finalUrl,

                                        validation:
                                            validation,

                                        title:
                                            finalDoc.title ||
                                            ""
                                    }
                                );

                        } catch (
                            error
                        ) {

                            finishError(
                                "CHECKOUT_ERROR",
                                String(
                                    error?.message ||
                                    error
                                )
                            );
                        }

                    }
                )();


                return "STARTED";

            })()
        """.trimIndent()
    }


    /*
     * ============================================================
     * UI STATE
     * ============================================================
     */
    private fun publish(
        phase: BookingPhase,
        message: String,
        running: Boolean,
        interaction: Boolean = false
    ) {
        val old =
            _state.value

        _state.value =
            old.copy(
                phase =
                    phase,
                message =
                    message,
                isRunning =
                    running,
                requiresUserInteraction =
                    interaction
            )
    }


    private fun appendLog(
        message: String
    ) {
        val old =
            _state.value

        _state.value =
            old.copy(
                log =
                    (
                            old.log +
                                    message
                            )
                        .takeLast(
                            20
                        )
            )
    }
}
