package com.paytm.seats;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes (snake_case via Jackson naming strategy). Money is always long paise. */
public final class Views {
    private Views() {}

    public record Reservation(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise, String status) {}

    public record ReserveResult(boolean replay, Reservation reservation) {}

    public record ShowConfig(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {}

    public record SeatState(String seat, String status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShowState(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
                            Map<String, Long> counts, boolean reconciled, List<SeatState> seats) {}
}
