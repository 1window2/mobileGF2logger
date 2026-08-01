// File Name: strict_properties.kt
// Role: Reject ambiguous duplicate keys while decoding trusted configuration formats.
package dev.gf2log.app

import java.util.Properties

// Class Name: StrictProperties
// Role: Properties container that fails closed when a serialized key occurs twice.
// Responsibilities:
//   - Duplicate detection: Reject duplicate logical keys, including escaped aliases.
// Attributes:
//   - documentName: User-facing name of the document being decoded.
internal class StrictProperties(
    private val documentName: String,
) : Properties() {
    override fun put(key: Any, value: Any): Any? {
        require(!containsKey(key)) { "$documentName contains duplicate field $key" }
        return super.put(key, value)
    }
}
