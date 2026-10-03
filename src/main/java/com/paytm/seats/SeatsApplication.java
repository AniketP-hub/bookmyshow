package com.paytm.seats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SeatsApplication {
    public static void main(String[] args) {
        LogBuffer.install(); // keep a tail of our own log lines for the optional public live-log view
        SpringApplication.run(SeatsApplication.class, args);
    }
}
