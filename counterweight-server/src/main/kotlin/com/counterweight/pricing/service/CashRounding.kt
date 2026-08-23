package com.counterweight.pricing.service

import com.counterweight.platform.service.ConfigKeys
import com.counterweight.platform.service.ConfigService
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Applies the configured cash-rounding increment.
 *
 * [Money] is deliberately a pure calculator with no idea what the shop is
 * configured to do; this is the piece that knows, and it is what the sale path
 * injects. Anything calling `Money.toCash` directly takes the fallback
 * increment and will not follow the setting when it changes.
 *
 * Read from `app_config`, which is the shop's own setting — the owner changes
 * it from the settings screen when small coins come back into circulation, and
 * waiting for a release to do that would be absurd. It used to live in
 * `application.yml` alongside a database row nothing read, so the two could
 * disagree; V9 removed the duplicate.
 */
@Component
class CashRounding(private val config: ConfigService) {

    /** [value] rounded to something the shop can actually hand over in coin. */
    fun apply(value: BigDecimal): BigDecimal = Money.toCash(value, increment)

    /** Signed difference the rounding introduced; belongs on `sale.rounding_adjustment`. */
    fun adjustment(value: BigDecimal): BigDecimal = Money.cashRounding(value, increment)

    val increment: BigDecimal
        get() = config.number(ConfigKeys.ROUNDING_INCREMENT, Money.DEFAULT_CASH_INCREMENT)
}
