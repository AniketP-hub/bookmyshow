package com.paytm.seats;

import com.paytm.seats.Views.*;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ApiController {
    private final SeatService svc;
    private final Auth auth;
    private final Lifecycle lifecycle;

    public ApiController(SeatService svc, Auth auth, Lifecycle lifecycle) {
        this.svc = svc;
        this.auth = auth;
        this.lifecycle = lifecycle;
    }

    private void ready() {
        if (!lifecycle.isMigrated()) throw new DomainException(503, "not_ready", "service is starting");
    }

    @PostMapping("/auth/token")
    public Map<String, String> token(@RequestBody(required = false) Map<String, Object> body) {
        Object u = body == null ? null : body.get("user_id");
        if (!(u instanceof String s) || !Auth.USER_ID.matcher(s).matches()) {
            throw new DomainException(400, "invalid_request", "user_id must match [A-Za-z0-9_.@-]{1,64}");
        }
        return Map.of("token", auth.issue(s), "user_id", s);
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowState> createShow(@RequestHeader(value = "Authorization", required = false) String authz,
                                                @RequestBody(required = false) Map<String, Object> body) {
        ready();
        auth.requireAdmin(authz);
        MDC.put("outcome", "created");
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.createShow(body == null ? Map.of() : body));
    }

    @GetMapping("/shows/{id}")
    public ShowState show(@PathVariable String id, @RequestParam(value = "seats", defaultValue = "true") boolean seats) {
        ready();
        return svc.showState(id, seats);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<Reservation> reserve(@PathVariable String id,
                                               @RequestHeader(value = "Authorization", required = false) String authz,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
                                               @RequestBody(required = false) Map<String, Object> body) {
        ready();
        String userId = auth.requireUser(authz); // identity: token only. Any user_id in the body is ignored.
        MDC.put("user_id", userId);
        Map<String, Object> b = body == null ? Map.of() : body;
        Object bodyKey = b.get("idempotency_key");
        if (bodyKey != null && !(bodyKey instanceof String)) throw new DomainException(400, "invalid_request", "idempotency_key must be a string");
        if (idemHeader != null && bodyKey != null && !idemHeader.equals(bodyKey)) {
            throw new DomainException(400, "invalid_request", "Idempotency-Key header and body idempotency_key disagree");
        }
        Object seatsRaw = b.get("seats");
        if (!(seatsRaw instanceof List<?> l) || !l.stream().allMatch(o -> o instanceof String)) {
            throw new DomainException(400, "invalid_request", "seats must be an array of strings");
        }

        ReserveResult r = svc.reserve(userId, id, (List<String>) l, idemHeader != null ? idemHeader : (String) bodyKey);
        MDC.put("outcome", r.replay() ? "idempotent_replay" : "confirmed");
        ResponseEntity.BodyBuilder rb = ResponseEntity.status(HttpStatus.CREATED);
        if (r.replay()) rb.header("Idempotent-Replayed", "true");
        return rb.body(r.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public Reservation cancel(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authz) {
        ready();
        String userId = auth.requireUser(authz);
        MDC.put("user_id", userId);
        Reservation r = svc.cancel(userId, id);
        MDC.put("outcome", "cancelled");
        return r;
    }

    @GetMapping("/reservations/{id}")
    public Reservation get(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authz) {
        ready();
        return svc.getReservation(auth.requireUser(authz), id);
    }
}
