package com.counterweight.platform.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * One setting the shop can change.
 *
 * Keyed by a dotted string rather than by a surrogate id: the key *is* the
 * identity, code refers to it by name, and a settings row whose key could be
 * edited would silently detach itself from whatever reads it.
 *
 * [valueType] is what a settings screen renders and what [ConfigService]
 * validates against on write. Storing everything as TEXT and declaring the type
 * alongside keeps one table for settings of every shape, which is worth more
 * than the column-per-type alternative that needs a migration per setting.
 */
@Entity
@Table(name = "app_config")
class AppConfig(
    @Id
    @Column(name = "key")
    var key: String,

    @Column(nullable = false)
    var value: String,
) {
    @Column(name = "value_type", nullable = false)
    var valueType: String = "STRING"

    var description: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is AppConfig && key == other.key)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "AppConfig($key)"
}
