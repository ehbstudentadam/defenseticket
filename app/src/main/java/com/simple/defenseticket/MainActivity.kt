package com.simple.defenseticket

import androidx.core.content.edit
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simple.defenseticket.ui.theme.DefenseTicketTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter


class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        setContent {

            DefenseTicketTheme {

                DefenseTicketApp()
            }
        }
    }
}


@Composable
fun DefenseTicketApp() {

    val context =
        LocalContext.current

    val scope =
        rememberCoroutineScope()


    /*
     * ========================================================
     * DATABASE
     * ========================================================
     */

    val database =
        remember {

            VoucherDatabaseHelper(
                context.applicationContext
            )
        }


    DisposableEffect(
        database
    ) {

        onDispose {

            database.close()
        }
    }


    var vouchers by remember {

        mutableStateOf(
            emptyList<Voucher>()
        )
    }


    suspend fun refreshVouchers() {

        vouchers =
            withContext(
                Dispatchers.IO
            ) {

                database.getAllVouchers()
            }
    }


    LaunchedEffect(
        Unit
    ) {

        refreshVouchers()
    }


    /*
     * ========================================================
     * BOOKING ENGINE
     * ========================================================
     */

    val bookingEngine =
        remember(
            database
        ) {

            NmbsBookingEngine(
                database
            )
        }


    val bookingState by
    bookingEngine
        .state
        .collectAsState()


    /*
     * ========================================================
     * PREFERENCES
     * ========================================================
     */

    val preferences =
        remember {

            context.getSharedPreferences(
                "defense_ticket_preferences",
                Context.MODE_PRIVATE
            )
        }


    val initiallyRememberDetails =
        remember {

            preferences.getBoolean(
                "remember_traveller_details",
                false
            )
        }


    /*
     * ========================================================
     * REIZIGER
     * ========================================================
     */

    var rememberDetails by rememberSaveable {

        mutableStateOf(
            initiallyRememberDetails
        )
    }


    var firstName by rememberSaveable {

        mutableStateOf(
            if (
                initiallyRememberDetails
            ) {

                preferences.getString(
                    "first_name",
                    ""
                ) ?: ""

            } else {

                ""
            }
        )
    }


    var lastName by rememberSaveable {

        mutableStateOf(
            if (
                initiallyRememberDetails
            ) {

                preferences.getString(
                    "last_name",
                    ""
                ) ?: ""

            } else {

                ""
            }
        )
    }


    var email by rememberSaveable {

        mutableStateOf(
            if (
                initiallyRememberDetails
            ) {

                preferences.getString(
                    "email",
                    ""
                ) ?: ""

            } else {

                ""
            }
        )
    }


    /*
     * ========================================================
     * REIS
     * ========================================================
     */

    var departure by rememberSaveable {
        mutableStateOf("")
    }


    var destination by rememberSaveable {
        mutableStateOf("")
    }


    var departureDate by rememberSaveable {
        mutableStateOf("")
    }


    var returnTrip by rememberSaveable {
        mutableStateOf(false)
    }


    var returnDate by rememberSaveable {
        mutableStateOf("")
    }


    var acceptedTerms by rememberSaveable {
        mutableStateOf(false)
    }


    /*
     * ========================================================
     * STATUS
     * ========================================================
     */

    var message by rememberSaveable {
        mutableStateOf("")
    }


    /*
     * ========================================================
     * GEGEVENS ONTHOUDEN
     * ========================================================
     */

    LaunchedEffect(
        rememberDetails,
        firstName,
        lastName,
        email
    ) {

        preferences.edit {

            putBoolean(
                "remember_traveller_details",
                rememberDetails
            )


            if (
                rememberDetails
            ) {

                putString(
                    "first_name",
                    firstName
                )

                putString(
                    "last_name",
                    lastName
                )

                putString(
                    "email",
                    email
                )

            } else {

                remove(
                    "first_name"
                )

                remove(
                    "last_name"
                )

                remove(
                    "email"
                )
            }
        }
    }


    /*
     * ========================================================
     * CSV
     * ========================================================
     */

    val csvPicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (
                uri != null
            ) {

                scope.launch {

                    message =
                        "Voucherbestand wordt verwerkt..."


                    try {

                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {

                                val parsed =
                                    parseVoucherCsv(
                                        context =
                                            context,

                                        uri =
                                            uri
                                    )


                                if (
                                    parsed.vouchers.isEmpty()
                                ) {

                                    throw IllegalArgumentException(
                                        "Geen geldige vouchers gevonden."
                                    )
                                }


                                val imported =
                                    database.importVouchers(
                                        parsed.vouchers
                                    )


                                Triple(
                                    parsed,
                                    imported,
                                    database.getAllVouchers()
                                )
                            }


                        vouchers =
                            result.third


                        message =
                            buildString {

                                append(
                                    "${result.second.inserted} nieuwe voucher(s)"
                                )

                                append(
                                    ", ${result.second.updated} bijgewerkt"
                                )


                                if (
                                    result.first.duplicateRows > 0
                                ) {

                                    append(
                                        ", ${result.first.duplicateRows} dubbele rij(en)"
                                    )
                                }


                                if (
                                    result.first.invalidRows > 0
                                ) {

                                    append(
                                        ", ${result.first.invalidRows} ongeldige rij(en)"
                                    )
                                }


                                append(".")
                            }

                    } catch (
                        exception: Exception
                    ) {

                        message =
                            "Import mislukt: " +
                                    (
                                            exception.message
                                                ?: "onbekende fout"
                                            )
                    }
                }
            }
        }


    /*
     * ========================================================
     * VOUCHERCOUNTERS
     * ========================================================
     */

    val today =
        LocalDate.now()


    val availableCount =
        vouchers.count {

            it.isUsable(
                today
            )
        }


    val usedCount =
        vouchers.count {

            it.status ==
                    VoucherStatus.USED
        }


    val invalidCount =
        vouchers.count {

            it.status ==
                    VoucherStatus.INVALID
        }


    val expiredCount =
        vouchers.count {

            it.status ==
                    VoucherStatus.AVAILABLE &&
                    it.isExpired(
                        today
                    )
        }


    /*
     * ========================================================
     * APP
     * ========================================================
     */

    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Scaffold(
            modifier =
                Modifier.fillMaxSize()
        ) { innerPadding ->


            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            innerPadding
                        )
                        .verticalScroll(
                            rememberScrollState()
                        )
                        .padding(
                            20.dp
                        ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        16.dp
                    )
            ) {


                Text(
                    text =
                        "Defense Ticket",

                    style =
                        MaterialTheme.typography.headlineMedium
                )


                Text(
                    text =
                        "NMBS woon/werk ticket"
                )


                /*
                 * =============================================
                 * REIZIGER
                 * =============================================
                 */

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                12.dp
                            )
                    ) {


                        Text(
                            text =
                                "Reiziger",

                            style =
                                MaterialTheme.typography.titleLarge
                        )


                        OutlinedTextField(
                            value =
                                firstName,

                            onValueChange = {
                                firstName = it
                            },

                            label = {
                                Text(
                                    "Voornaam"
                                )
                            },

                            singleLine =
                                true,

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        OutlinedTextField(
                            value =
                                lastName,

                            onValueChange = {
                                lastName = it
                            },

                            label = {
                                Text(
                                    "Achternaam"
                                )
                            },

                            singleLine =
                                true,

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        OutlinedTextField(
                            value =
                                email,

                            onValueChange = {
                                email = it
                            },

                            label = {
                                Text(
                                    "E-mail"
                                )
                            },

                            singleLine =
                                true,

                            enabled =
                                !bookingState.isRunning,

                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        KeyboardType.Email
                                ),

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Checkbox(
                                checked =
                                    rememberDetails,

                                onCheckedChange = {
                                    rememberDetails =
                                        it
                                },

                                enabled =
                                    !bookingState.isRunning
                            )


                            Text(
                                text =
                                    "Onthoud deze gegevens"
                            )
                        }
                    }
                }


                /*
                 * =============================================
                 * REIS
                 * =============================================
                 */

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                12.dp
                            )
                    ) {


                        Text(
                            text =
                                "Reis",

                            style =
                                MaterialTheme.typography.titleLarge
                        )


                        OutlinedTextField(
                            value =
                                departure,

                            onValueChange = {
                                departure =
                                    it
                            },

                            label = {
                                Text(
                                    "Van"
                                )
                            },

                            placeholder = {
                                Text(
                                    "NMBS-station"
                                )
                            },

                            singleLine =
                                true,

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        OutlinedTextField(
                            value =
                                destination,

                            onValueChange = {
                                destination =
                                    it
                            },

                            label = {
                                Text(
                                    "Naar"
                                )
                            },

                            placeholder = {
                                Text(
                                    "NMBS-station"
                                )
                            },

                            singleLine =
                                true,

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        OutlinedTextField(
                            value =
                                departureDate,

                            onValueChange = {},

                            readOnly =
                                true,

                            label = {
                                Text(
                                    "Datum heenrit"
                                )
                            },

                            modifier =
                                Modifier.fillMaxWidth()
                        )


                        OutlinedButton(
                            onClick = {

                                showDatePicker(
                                    context =
                                        context,

                                    currentDate =
                                        departureDate
                                ) {

                                    departureDate =
                                        it


                                    if (
                                        returnTrip &&
                                        returnDate.isBlank()
                                    ) {

                                        returnDate =
                                            it
                                    }
                                }
                            },

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Text(
                                "Kies datum heenrit"
                            )
                        }


                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Checkbox(
                                checked =
                                    returnTrip,

                                onCheckedChange = {

                                    returnTrip =
                                        it


                                    if (
                                        it &&
                                        returnDate.isBlank()
                                    ) {

                                        returnDate =
                                            departureDate
                                    }


                                    if (
                                        !it
                                    ) {

                                        returnDate =
                                            ""
                                    }
                                },

                                enabled =
                                    !bookingState.isRunning
                            )


                            Text(
                                text =
                                    "Heen en terug"
                            )
                        }


                        if (
                            returnTrip
                        ) {

                            OutlinedTextField(
                                value =
                                    returnDate,

                                onValueChange = {},

                                readOnly =
                                    true,

                                label = {
                                    Text(
                                        "Datum terugrit"
                                    )
                                },

                                modifier =
                                    Modifier.fillMaxWidth()
                            )


                            OutlinedButton(
                                onClick = {

                                    showDatePicker(
                                        context =
                                            context,

                                        currentDate =
                                            returnDate
                                    ) {

                                        returnDate =
                                            it
                                    }
                                },

                                enabled =
                                    !bookingState.isRunning,

                                modifier =
                                    Modifier.fillMaxWidth()
                            ) {

                                Text(
                                    "Kies datum terugrit"
                                )
                            }
                        }
                    }
                }


                /*
                 * =============================================
                 * VOUCHERS
                 * =============================================
                 */

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {

                        Text(
                            text =
                                "Vouchers",

                            style =
                                MaterialTheme.typography.titleLarge
                        )


                        Text(
                            "Beschikbaar: $availableCount"
                        )


                        Text(
                            "Gebruikt: $usedCount"
                        )


                        Text(
                            "Verlopen: $expiredCount"
                        )


                        Text(
                            "Ongeldig: $invalidCount"
                        )


                        Text(
                            "Totaal: ${vouchers.size}"
                        )


                        Button(
                            onClick = {

                                csvPicker.launch(
                                    arrayOf(
                                        "*/*"
                                    )
                                )
                            },

                            enabled =
                                !bookingState.isRunning,

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Text(
                                "CSV met vouchers importeren"
                            )
                        }


                        vouchers
                            .take(
                                10
                            )
                            .forEach {

                                VoucherRow(
                                    voucher =
                                        it,

                                    today =
                                        today
                                )
                            }


                        if (
                            vouchers.size > 10
                        ) {

                            Text(
                                "… en nog ${vouchers.size - 10} voucher(s)"
                            )
                        }
                    }
                }


                /*
                 * =============================================
                 * NMBS VOORWAARDEN
                 * =============================================
                 */

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(
                                12.dp
                            )
                    ) {

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Checkbox(
                                checked =
                                    acceptedTerms,

                                onCheckedChange = {
                                    acceptedTerms =
                                        it
                                },

                                enabled =
                                    !bookingState.isRunning
                            )


                            Text(
                                text =
                                    "Ik ga akkoord met de NMBS-verkoopsvoorwaarden"
                            )
                        }


                        TextButton(
                            onClick = {

                                val intent =
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://www.belgiantrain.be/nl/support/general-sales-conditions".toUri()
                                    )


                                context.startActivity(
                                    intent
                                )
                            }
                        ) {

                            Text(
                                "Bekijk voorwaarden"
                            )
                        }
                    }
                }


                /*
                 * =============================================
                 * BOEKSTATUS
                 * =============================================
                 */

                if (
                    bookingState.phase !=
                    BookingPhase.IDLE
                ) {

                    Card(
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(
                                    16.dp
                                ),

                            verticalArrangement =
                                Arrangement.spacedBy(
                                    8.dp
                                )
                        ) {


                            if (
                                bookingState.isRunning
                            ) {

                                CircularProgressIndicator()
                            }


                            Text(
                                text =
                                    bookingState.message,

                                style =
                                    MaterialTheme.typography.titleMedium
                            )


                            bookingState.log.forEach {

                                Text(
                                    text =
                                        "• $it"
                                )
                            }


                            if (
                                !bookingState.isRunning
                            ) {

                                OutlinedButton(
                                    onClick = {

                                        bookingEngine.reset()
                                    }
                                ) {

                                    Text(
                                        "Sluiten"
                                    )
                                }
                            }
                        }
                    }
                }


                /*
                 * =============================================
                 * BOEKKNOP
                 * =============================================
                 */

                Button(
                    onClick = {

                        val requiredVouchers =
                            if (
                                returnTrip
                            ) {
                                2
                            } else {
                                1
                            }


                        val formatter =
                            DateTimeFormatter.ofPattern(
                                "dd/MM/yyyy"
                            )


                        when {

                            firstName.isBlank() -> {

                                message =
                                    "Vul je voornaam in."
                            }


                            firstName.length > 25 -> {

                                message =
                                    "Voornaam mag maximaal 25 tekens bevatten."
                            }


                            lastName.isBlank() -> {

                                message =
                                    "Vul je achternaam in."
                            }


                            lastName.length > 25 -> {

                                message =
                                    "Achternaam mag maximaal 25 tekens bevatten."
                            }


                            email.isBlank() -> {

                                message =
                                    "Vul je e-mailadres in."
                            }


                            email.length > 50 -> {

                                message =
                                    "E-mailadres mag maximaal 50 tekens bevatten."
                            }


                            !Patterns.EMAIL_ADDRESS
                                .matcher(
                                    email
                                )
                                .matches() -> {

                                message =
                                    "Vul een geldig e-mailadres in."
                            }


                            departure.isBlank() -> {

                                message =
                                    "Vul een vertrekstation in."
                            }


                            destination.isBlank() -> {

                                message =
                                    "Vul een bestemmingsstation in."
                            }


                            departure.equals(
                                destination,
                                ignoreCase = true
                            ) -> {

                                message =
                                    "Vertrek en bestemming mogen niet hetzelfde zijn."
                            }


                            departureDate.isBlank() -> {

                                message =
                                    "Kies een datum voor de heenrit."
                            }


                            returnTrip &&
                                    returnDate.isBlank() -> {

                                message =
                                    "Kies een datum voor de terugrit."
                            }


                            returnTrip &&
                                    !isReturnDateValid(
                                        departureDate,
                                        returnDate
                                    ) -> {

                                message =
                                    "Terugrit kan niet vóór de heenrit liggen."
                            }


                            availableCount <
                                    requiredVouchers -> {

                                message =
                                    "Niet genoeg beschikbare vouchers."
                            }


                            !acceptedTerms -> {

                                message =
                                    "Accepteer eerst de NMBS-verkoopsvoorwaarden."
                            }


                            else -> {

                                message =
                                    ""


                                val request =
                                    BookingRequest(
                                        firstName =
                                            firstName.trim(),

                                        lastName =
                                            lastName.trim(),

                                        email =
                                            email.trim(),

                                        departure =
                                            departure.trim(),

                                        destination =
                                            destination.trim(),

                                        departureDate =
                                            LocalDate.parse(
                                                departureDate,
                                                formatter
                                            ),

                                        returnDate =
                                            if (
                                                returnTrip
                                            ) {

                                                LocalDate.parse(
                                                    returnDate,
                                                    formatter
                                                )

                                            } else {

                                                null
                                            }
                                    )


                                scope.launch {

                                    bookingEngine.book(
                                        request
                                    )


                                    refreshVouchers()
                                }
                            }
                        }
                    },

                    enabled =
                        !bookingState.isRunning,

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        text =
                            if (
                                returnTrip
                            ) {
                                "Heen- en terugtickets boeken"
                            } else {
                                "Ticket boeken"
                            }
                    )
                }


                if (
                    message.isNotBlank()
                ) {

                    Card(
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Text(
                            text =
                                message,

                            modifier =
                                Modifier.padding(
                                    16.dp
                                )
                        )
                    }
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            30.dp
                        )
                )
            }
        }


        /*
         * ====================================================
         * ONZICHTBARE NMBS BROWSERENGINE
         * ====================================================
         *
         * Komt alleen zichtbaar boven de app wanneer NMBS
         * werkelijk menselijke browserinteractie vraagt.
         */

        NmbsSessionWebView(
            engine =
                bookingEngine,

            visible =
                bookingState
                    .requiresUserInteraction
        )
    }
}


/*
 * ============================================================
 * VOUCHER UI
 * ============================================================
 */

@Composable
fun VoucherRow(
    voucher: Voucher,
    today: LocalDate
) {

    val status =
        when {

            voucher.status ==
                    VoucherStatus.USED ->
                "Gebruikt"

            voucher.status ==
                    VoucherStatus.INVALID ->
                "Ongeldig"

            voucher.isExpired(
                today
            ) ->
                "Verlopen"

            else ->
                "Beschikbaar"
        }


    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(
                    10.dp
                )
        ) {

            Text(
                text =
                    maskVoucherCode(
                        voucher.code
                    )
            )


            Text(
                text =
                    status,

                style =
                    MaterialTheme.typography.bodySmall
            )


            Text(
                text =
                    "Vervalt: ${
                        voucher.expirationDate.format(
                            DateTimeFormatter.ofPattern(
                                "dd/MM/yyyy"
                            )
                        )
                    }",

                style =
                    MaterialTheme.typography.bodySmall
            )


            if (
                voucher.failureCount > 0
            ) {

                Text(
                    text =
                        "Tijdelijke fouten: ${voucher.failureCount}",

                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}


/*
 * ============================================================
 * DATE PICKER
 * ============================================================
 */

fun showDatePicker(
    context: Context,
    currentDate: String,
    onDateSelected: (String) -> Unit
) {

    val formatter =
        DateTimeFormatter.ofPattern(
            "dd/MM/yyyy"
        )


    val initial =
        try {

            LocalDate.parse(
                currentDate,
                formatter
            )

        } catch (
            _: Exception
        ) {

            LocalDate.now()
        }


    val dialog =
        DatePickerDialog(
            context,

            { _, year, month, day ->

                val date =
                    LocalDate.of(
                        year,
                        month + 1,
                        day
                    )


                onDateSelected(
                    date.format(
                        formatter
                    )
                )
            },

            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        )


    dialog.datePicker.minDate =
        System.currentTimeMillis() -
                1000


    dialog.show()
}


/*
 * ============================================================
 * RETURN DATE
 * ============================================================
 */

fun isReturnDateValid(
    departureDate: String,
    returnDate: String
): Boolean {

    return try {

        val formatter =
            DateTimeFormatter.ofPattern(
                "dd/MM/yyyy"
            )


        val departure =
            LocalDate.parse(
                departureDate,
                formatter
            )


        val returning =
            LocalDate.parse(
                returnDate,
                formatter
            )


        !returning.isBefore(
            departure
        )

    } catch (
        _: Exception
    ) {

        false
    }
}


/*
 * ============================================================
 * VOUCHER MASK
 * ============================================================
 */

fun maskVoucherCode(
    code: String
): String {

    if (
        code.length <= 7
    ) {

        return "••••••"
    }


    return buildString {

        append(
            code.take(
                3
            )
        )


        append(
            "••••••"
        )


        append(
            code.takeLast(
                4
            )
        )
    }
}