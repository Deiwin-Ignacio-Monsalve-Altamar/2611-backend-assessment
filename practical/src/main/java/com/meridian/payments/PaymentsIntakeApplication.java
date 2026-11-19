package com.meridian.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Meridian Mutual Payments Intake service.
 * Scans subpackages (controller, service, model) to wire dependencies automatically.
 */
@SpringBootApplication
public class PaymentsIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsIntakeApplication.class, args);
    }
}
