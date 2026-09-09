package com.alive.player.worker

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which install-failure statuses are treated as deterministic. Widening the
 * permanent set risks poisoning updates that would have succeeded on retry;
 * shrinking it reintroduces the forever re-commit loop this classification exists
 * to stop (a silent-capable device re-streaming a signature-mismatched build every
 * periodic check). PackageInstaller.STATUS_* are compile-time int constants, so
 * this resolves in plain JVM tests. As with UpdateInstallerGuardTest, the wiring
 * (the receiver's else-branch consulting this) is by inspection.
 *
 * Every expectation below is traceable to PackageManager.installStatusToPublicStatus(),
 * which is the function that decides which coarse STATUS_FAILURE_* a given
 * INSTALL_FAILED_* is delivered as. An earlier version of this file asserted the
 * opposite of the signature-mismatch case and pinned the bug green — so the mapping
 * is cited per-case rather than assumed.
 */
class InstallFailureClassificationTest {

    @Test
    fun `signature mismatch on update is permanent`() {
        // THE case this classifier exists for. installStatusToPublicStatus maps
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE -> STATUS_FAILURE_CONFLICT, and that is
        // what a build signed with the wrong key returns: the canonical
        // never-installable APK for a self-updating kiosk, and exactly what a
        // signing-key migration produces fleet-wide.
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_CONFLICT))
    }

    @Test
    fun `malformed apk and device-capability mismatches are permanent`() {
        // INVALID <- INVALID_APK, TEST_ONLY, VERSION_DOWNGRADE, BAD_SIGNATURE, PARSE_FAILED_*
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_INVALID))
        // INCOMPATIBLE <- OLDER_SDK, NEWER_SDK, CPU_ABI_INCOMPATIBLE, MISSING_FEATURE,
        // NO_MATCHING_ABIS, MISSING_SPLIT. None of these change without a new build.
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
    }

    @Test
    fun `environmental failures stay retryable`() {
        // FAILURE <- INTERNAL_ERROR; STORAGE <- INSUFFICIENT_STORAGE and friends;
        // BLOCKED <- PRE_APPROVAL_NOT_AVAILABLE. All can clear without a new build.
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_STORAGE))
    }

    @Test
    fun `a temporary admin restriction is not permanent despite arriving as INCOMPATIBLE`() {
        // INSTALL_FAILED_USER_RESTRICTED is mapped into the INCOMPATIBLE bucket
        // alongside genuinely permanent device-capability failures, but it lifts when
        // policy changes. Without the legacy code the public status is ambiguous and
        // we (correctly) fall back to permanent; with it, this must stay retryable —
        // otherwise the durable poison strands a panel over a transient policy state.
        assertFalse(
            UpdateInstaller.isPermanentInstallFailure(
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                UpdateInstaller.INSTALL_FAILED_USER_RESTRICTED,
            )
        )
        // Same status, a DIFFERENT legacy cause (e.g. NO_MATCHING_ABIS = -113) stays
        // permanent — the carve-out must be narrow, not "INCOMPATIBLE is retryable".
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, -113))
    }

    @Test
    fun `attaching a legacy code never makes a retryable status permanent`() {
        // Guards the direction the carve-out could regress in: statuses that are
        // retryable on the public status alone must stay retryable whatever legacy
        // code rides along. STORAGE (space frees up) and FAILURE (INTERNAL_ERROR,
        // -110) are the two a stuck panel realistically sees.
        assertFalse(
            UpdateInstaller.isPermanentInstallFailure(
                PackageInstaller.STATUS_FAILURE_STORAGE,
                UpdateInstaller.INSTALL_FAILED_USER_RESTRICTED,
            )
        )
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE, -110))
    }

    @Test
    fun `absent legacy status falls back to the public status`() {
        // OEMs that omit EXTRA_LEGACY_STATUS must still get correct classification
        // from the coarse status alone — and the sentinel must never collide with a
        // real code, all of which are small negatives.
        assertTrue(
            UpdateInstaller.isPermanentInstallFailure(
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                UpdateInstaller.LEGACY_STATUS_ABSENT,
            )
        )
        assertFalse(
            UpdateInstaller.isPermanentInstallFailure(
                PackageInstaller.STATUS_FAILURE_STORAGE,
                UpdateInstaller.LEGACY_STATUS_ABSENT,
            )
        )
        assertTrue(UpdateInstaller.LEGACY_STATUS_ABSENT < -100_000)
    }

    @Test
    fun `non-failure statuses are not permanent failures`() {
        // Handled by earlier when-branches in the receiver; the classifier must
        // still answer sanely if ever consulted first. ABORTED in particular is the
        // operator pressing Cancel — a choice, not a defect.
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_SUCCESS))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_ABORTED))
    }
}
