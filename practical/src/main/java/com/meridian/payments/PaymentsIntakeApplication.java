package com.meridian.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class PaymentsIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsIntakeApplication.class, args);
    }

    @GetMapping("/health")
    public String health() {
        return "payments-intake is running";
    }
}
