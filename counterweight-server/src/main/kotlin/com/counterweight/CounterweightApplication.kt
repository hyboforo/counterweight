package com.counterweight

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Counterweight — POS and inventory control for a hardware and agro-chemical shop.
 *
 * Runs on a single machine inside the shop. Nothing on the sale path may require
 * a network hop beyond the shop switch; see §1 of docs/architecture.html.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// Alerting's nightly evaluators and the expiry sweep run from here (§11).
@EnableScheduling
class CounterweightApplication

fun main(args: Array<String>) {
    runApplication<CounterweightApplication>(*args)
}
