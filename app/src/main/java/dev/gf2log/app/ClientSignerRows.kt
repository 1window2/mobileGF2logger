package dev.gf2log.app

import android.view.View
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.navigationRow

/**
 * Settings' client signature rows: each supported client's trust state, and for a changed or not
 * yet trusted signer the one way to trust it, a confirmation that names the certificate it pins.
 */
internal class ClientSignerRows(private val activity: BaseActivity) {

    fun view(): View {
        val signers = SupportedClientSigners.of(activity)
        return activity.group(SupportedGamePackages.all.map { packageName -> row(signers, packageName) })
    }

    private fun row(signers: SupportedClientSigner, packageName: String): View {
        val client = clientName(packageName)
        val trust = signers.trust(packageName)
        val pinned = signers.pinned(packageName)
        val detail = when (trust) {
            SupportedClientTrust.VERIFIED ->
                activity.getString(R.string.client_signer_trusted, SupportedClientSignerPolicy.fingerprint(pinned.orEmpty()))
            SupportedClientTrust.NOT_INSTALLED -> pinned
                ?.let { activity.getString(R.string.client_signer_not_installed_pinned, SupportedClientSignerPolicy.fingerprint(it)) }
                ?: activity.getString(R.string.client_not_installed)
            SupportedClientTrust.UNPINNED -> activity.getString(R.string.client_signer_unpinned)
            SupportedClientTrust.MISMATCH -> activity.getString(R.string.client_signer_changed)
        }
        val reviewable = trust == SupportedClientTrust.MISMATCH || trust == SupportedClientTrust.UNPINNED
        return activity.navigationRow(
            activity.getString(R.string.client_signer_title, client),
            detail,
            if (reviewable) R.drawable.ic_warning else R.drawable.ic_shield,
            showChevron = reviewable,
            onClick = if (reviewable) ({ review(packageName) }) else null,
        )
    }

    /** Shows the installed certificate and pins exactly that one when the user confirms it. */
    private fun review(packageName: String) {
        val signers = SupportedClientSigners.of(activity)
        val client = clientName(packageName)
        val shown = signers.current(packageName)?.takeIf { it.isNotEmpty() } ?: return refresh()
        val pinned = signers.pinned(packageName)
        val message = if (pinned == null) {
            activity.getString(R.string.client_signer_unpinned_message, client, SupportedClientSignerPolicy.fingerprint(shown))
        } else {
            activity.getString(
                R.string.client_signer_changed_message,
                client,
                SupportedClientSignerPolicy.fingerprint(pinned),
                SupportedClientSignerPolicy.fingerprint(shown),
            )
        }
        Dialogs.confirm(
            activity,
            activity.getString(R.string.client_signer_review_title, client),
            message,
            activity.getString(R.string.client_signer_trust),
        ) {
            val trusted = SupportedClientSigners.of(activity).retrust(packageName, shown)
            Feedback.show(
                activity,
                activity.getString(if (trusted) R.string.client_signer_trusted_feedback else R.string.client_signer_changed_again, client),
                long = !trusted,
            )
            refresh()
        }
    }

    private fun clientName(packageName: String) = PlatoonClient.fromPackage(packageName)?.displayName ?: packageName

    private fun refresh() {
        if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
    }
}
