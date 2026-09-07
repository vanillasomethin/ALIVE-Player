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
 */
class InstallFailureClassificationTest {

    @Test
    fun `malformed apk and signature mismatch are permanent`() {
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_INVALID))
        assertTrue(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
    }

    @Test
    fun `environmental failures stay retryable`() {
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_STORAGE))
    }

    @Test
    fun `non-failure statuses are not permanent failures`() {
        // Handled by earlier when-branches in the receiver; the classifier must
        // still answer sanely if ever consulted first.
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_SUCCESS))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertFalse(UpdateInstaller.isPermanentInstallFailure(PackageInstaller.STATUS_FAILURE_ABORTED))
    }
}
