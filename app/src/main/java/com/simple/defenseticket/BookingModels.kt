package com.simple.defenseticket

import java.time.LocalDate


data class BookingRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val departure: String,
    val destination: String,
    val departureDate: LocalDate,
    val returnDate: LocalDate? = null
)


data class SingleTripRequest(
    val departure: String,
    val destination: String,
    val date: LocalDate
)


enum class BookingPhase {
    IDLE,
    STARTING,
    SEARCHING,
    CHECKING_VOUCHER,
    CHECKOUT,
    WAITING_SUCCESS,
    NEEDS_INTERACTION,
    SUCCESS,
    FAILED
}


data class BookingUiState(
    val phase: BookingPhase = BookingPhase.IDLE,
    val message: String = "",
    val isRunning: Boolean = false,
    val requiresUserInteraction: Boolean = false,
    val log: List<String> = emptyList()
)