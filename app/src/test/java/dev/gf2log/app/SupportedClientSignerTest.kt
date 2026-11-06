package dev.gf2log.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedClientSignerTest {

    private val genuine = "a".repeat(64)
    private val rotated = "b".repeat(64)
    private val foreign = "c".repeat(64)

    /** Installed packages and their signers; a lineage lists the certificates a package rotated away from. */
    private class FakeSource : ClientSigningSource {
        val installed = HashMap<String, Set<String>>()
        val lineage = HashMap<String, Set<String>>()

        override fun currentDigests(packageName: String): Set<String>? = installed[packageName]

        override fun hasSigner(packageName: String, digest: String): Boolean {
            val current = installed[packageName] ?: return false
            return digest in current || digest in lineage[packageName].orEmpty()
        }
    }

    private class MemoryPins : SupportedClientPins {
        val stored = HashMap<String, Set<String>>()
        override fun read(packageName: String): Set<String>? = stored[packageName]
        override fun write(packageName: String, digests: Set<String>) {
            stored[packageName] = digests.toSet()
        }
    }

    private val source = FakeSource()
    private val pins = MemoryPins()
    private val signer = SupportedClientSigner(pins, source)

    @Test
    fun `an absent client is not installed and nothing is pinned for it`() {
        assertEquals(SupportedClientTrust.NOT_INSTALLED, signer.trust(SupportedGamePackages.HAOPLAY))
        assertEquals(emptyList<String>(), signer.pinFirstSeen())
        assertTrue(pins.stored.isEmpty())
        assertFalse(signer.isVerified(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `an installed client is untrusted until it is seen while the app is in use`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)

        assertEquals(SupportedClientTrust.UNPINNED, signer.trust(SupportedGamePackages.HAOPLAY))
        assertEquals(emptyList<String>(), signer.verifiedPackages())

        assertEquals(listOf(SupportedGamePackages.HAOPLAY), signer.pinFirstSeen())
        assertEquals(setOf(genuine), pins.stored[SupportedGamePackages.HAOPLAY])
        assertEquals(SupportedClientTrust.VERIFIED, signer.trust(SupportedGamePackages.HAOPLAY))
        assertEquals(listOf(SupportedGamePackages.HAOPLAY), signer.verifiedPackages())
    }

    @Test
    fun `another signer under the pinned package name is a mismatch and is not re-pinned`() {
        source.installed[SupportedGamePackages.DARKWINTER] = setOf(genuine)
        signer.pinFirstSeen()
        source.installed[SupportedGamePackages.DARKWINTER] = setOf(foreign)

        assertEquals(SupportedClientTrust.MISMATCH, signer.trust(SupportedGamePackages.DARKWINTER))
        assertEquals(emptyList<String>(), signer.pinFirstSeen())
        assertEquals(setOf(genuine), pins.stored[SupportedGamePackages.DARKWINTER])
        assertEquals(emptyList<String>(), signer.verifiedPackages())
    }

    @Test
    fun `uninstalling keeps the pin so only the same signer's reinstall matches`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        signer.pinFirstSeen()

        source.installed.remove(SupportedGamePackages.HAOPLAY)
        assertEquals(SupportedClientTrust.NOT_INSTALLED, signer.trust(SupportedGamePackages.HAOPLAY))
        assertEquals(setOf(genuine), signer.pinned(SupportedGamePackages.HAOPLAY))

        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        assertEquals(SupportedClientTrust.VERIFIED, signer.trust(SupportedGamePackages.HAOPLAY))

        source.installed.remove(SupportedGamePackages.HAOPLAY)
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(foreign)
        signer.pinFirstSeen()
        assertEquals(SupportedClientTrust.MISMATCH, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `a key rotation the client's lineage vouches for still verifies`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        signer.pinFirstSeen()

        source.installed[SupportedGamePackages.HAOPLAY] = setOf(rotated)
        source.lineage[SupportedGamePackages.HAOPLAY] = setOf(genuine)

        assertEquals(SupportedClientTrust.VERIFIED, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `every pinned signer of a multi-signer client must still sign it`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine, rotated)
        signer.pinFirstSeen()

        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine, foreign)

        assertEquals(SupportedClientTrust.MISMATCH, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `a client reporting no signer is never pinned or verified`() {
        source.installed[SupportedGamePackages.HAOPLAY] = emptySet()

        assertEquals(emptyList<String>(), signer.pinFirstSeen())
        assertEquals(SupportedClientTrust.UNPINNED, signer.trust(SupportedGamePackages.HAOPLAY))
        assertFalse(signer.retrust(SupportedGamePackages.HAOPLAY, emptySet()))

        pins.stored[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        assertEquals(SupportedClientTrust.MISMATCH, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `malformed stored pins count as no pin`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        pins.stored[SupportedGamePackages.HAOPLAY] = setOf("not a digest", genuine.uppercase())

        assertNull(signer.pinned(SupportedGamePackages.HAOPLAY))
        assertEquals(SupportedClientTrust.UNPINNED, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `re-trusting pins exactly the signer the user was shown`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        signer.pinFirstSeen()
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(foreign)
        val shown = requireNotNull(signer.current(SupportedGamePackages.HAOPLAY))

        assertTrue(signer.retrust(SupportedGamePackages.HAOPLAY, shown))

        assertEquals(setOf(foreign), pins.stored[SupportedGamePackages.HAOPLAY])
        assertEquals(SupportedClientTrust.VERIFIED, signer.trust(SupportedGamePackages.HAOPLAY))
    }

    @Test
    fun `re-trusting is refused when the client changed behind the confirmation`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        signer.pinFirstSeen()
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(foreign)
        val shown = requireNotNull(signer.current(SupportedGamePackages.HAOPLAY))

        source.installed[SupportedGamePackages.HAOPLAY] = setOf(rotated)
        assertFalse(signer.retrust(SupportedGamePackages.HAOPLAY, shown))

        source.installed.remove(SupportedGamePackages.HAOPLAY)
        assertFalse(signer.retrust(SupportedGamePackages.HAOPLAY, shown))

        assertEquals(setOf(genuine), pins.stored[SupportedGamePackages.HAOPLAY])
    }

    @Test
    fun `each client is pinned and judged on its own`() {
        source.installed[SupportedGamePackages.HAOPLAY] = setOf(genuine)
        signer.pinFirstSeen()
        source.installed[SupportedGamePackages.DARKWINTER] = setOf(foreign)

        assertEquals(SupportedClientTrust.UNPINNED, signer.trust(SupportedGamePackages.DARKWINTER))
        assertEquals(listOf(SupportedGamePackages.DARKWINTER), signer.pinFirstSeen())
        assertEquals(SupportedGamePackages.all, signer.verifiedPackages())
    }

    @Test
    fun `the fingerprint shows a short grouped prefix of each digest`() {
        val digest = "0123456789abcdef" + "0".repeat(48)

        assertEquals("0123 4567 89AB CDEF…", SupportedClientSignerPolicy.fingerprint(setOf(digest)))
        assertEquals(
            "0123 4567 89AB CDEF…, AAAA AAAA AAAA AAAA…",
            SupportedClientSignerPolicy.fingerprint(setOf(genuine, digest)),
        )
    }

    @Test
    fun `only lowercase sixty-four digit hex is a digest`() {
        assertTrue(SupportedClientSignerPolicy.isDigest(genuine))
        assertFalse(SupportedClientSignerPolicy.isDigest(genuine.uppercase()))
        assertFalse(SupportedClientSignerPolicy.isDigest(genuine.drop(1)))
        assertFalse(SupportedClientSignerPolicy.isDigest("g".repeat(64)))
    }
}
