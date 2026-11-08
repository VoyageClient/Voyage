/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.safeOpenOutputStream
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogExportE2eKeysBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.imagepack.edit.ImagePackProgressDialog
import im.vector.app.features.notifications.NotificationUtils
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsBackupFragment : DatabaseRestoreSettingsFragment() {

    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var clock: Clock

    override var titleRes = CommonStrings.settings_backup_title
    override val preferenceXmlRes = R.xml.vector_settings_backup

    private var pendingPassphrase: String? = null

    // Outlives the fragment, so the end of a long export can still be reported.
    private val appContext by lazy { requireContext().applicationContext }

    private val settingsExportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runSettingsExport(uri) { preferencesBackupManager.exportTo(uri) }
        }
    }

    private val settingsImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            readSettingsBackup(uri)
        }
    }

    private val databaseExportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runDatabaseExport(
                    open = { requireContext().safeOpenOutputStream(uri) ?: throw IOException("Cannot open $uri") },
                    // The picker creates the document up front, so a failed export must not leave it behind.
                    onFailure = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                        }
                    },
                    onSuccess = {
                        preferencesBackupManager.lastLocation = uri
                        appContext.toast(CommonStrings.settings_backup_database_export_done)
                    },
            )
        }
    }

    override fun bindPref() {
        onClick("SETTINGS_BACKUP_EXPORT_KEY") { exportSettings() }
        onClick("SETTINGS_BACKUP_IMPORT_KEY") { pickFileToImport(settingsImportLauncher, arrayOf(JSON_MIME_TYPE)) }
        onClick("SETTINGS_BACKUP_DATABASE_EXPORT_KEY") { askExportPassphrase() }
        bindDatabaseImport()
    }

    private fun createDocumentIntent(fileName: String, mimeType: String): Intent = withLastLocation(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_TITLE, fileName)
    )

    // No colons: FAT-formatted storage rejects them in file names.
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(clock.epochMillis()))

    // ---- Settings ----

    private fun exportSettings() {
        val fileName = "voyage-settings-${timestamp()}.json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && startPicker(settingsExportLauncher, createDocumentIntent(fileName, JSON_MIME_TYPE))) return
        // No SAF before KitKat: save straight to Downloads.
        runSettingsExport(location = null) {
            val file = File(requireContext().cacheDir, fileName)
            try {
                preferencesBackupManager.exportToFile(file)
                saveMedia(requireContext(), file, fileName, JSON_MIME_TYPE, notificationUtils, clock.epochMillis())
            } finally {
                file.delete()
            }
        }
    }

    private fun runSettingsExport(location: Uri?, write: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                write()
                location?.let { preferencesBackupManager.lastLocation = it }
                context?.toast(CommonStrings.settings_backup_export_done)
            } catch (e: Exception) {
                Timber.e(e, "Settings export failed")
                context?.toast(CommonStrings.settings_backup_export_failed)
            }
        }
    }

    private fun readSettingsBackup(uri: Uri) {
        lifecycleScope.launch {
            val backup = try {
                preferencesBackupManager.read(uri)
            } catch (e: Exception) {
                Timber.e(e, "Settings import failed")
                context?.toast(CommonStrings.settings_backup_import_invalid)
                return@launch
            }
            preferencesBackupManager.lastLocation = uri
            val signedIn = preferencesBackupManager.signedInUserIds()
            if (isAdded) showSettingsImportChoices(backup, signedIn)
        }
    }

    private fun showSettingsImportChoices(backup: PreferencesBackupFile, signedIn: Set<String>) {
        val userIds = backup.accounts.keys.toList()
        val labels = listOf(getString(CommonStrings.settings_backup_import_device)) + userIds.map {
            if (it in signedIn) it else getString(CommonStrings.settings_backup_import_not_signed_in, it)
        }
        val checked = BooleanArray(labels.size) { true }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_backup_import_choose)
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(CommonStrings.settings_backup_import_action) { _, _ ->
                    val selected = userIds.filterIndexed { i, _ -> checked[i + 1] }.toSet()
                    if (!checked[0] && selected.isEmpty()) return@setPositiveButton
                    val activity = requireActivity()
                    activity.lifecycleScope.launch {
                        try {
                            preferencesBackupManager.restore(backup, restoreGlobal = checked[0], userIds = selected)
                        } catch (e: Exception) {
                            Timber.e(e, "Settings import failed")
                            activity.toast(CommonStrings.settings_backup_import_failed)
                            return@launch
                        }
                        MainActivity.restartProcess(activity)
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    // ---- Database ----

    private fun askExportPassphrase() {
        val views = DialogExportE2eKeysBinding.inflate(layoutInflater)
        views.exportDialogText.setText(CommonStrings.settings_backup_database_export_notice)
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_backup_database_export_title)
                .setView(views.root)
                .setPositiveButton(CommonStrings.encryption_export_export) { _, _ ->
                    exportDatabase(views.exportDialogEt.text?.toString().orEmpty().ifEmpty { null })
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
        val exportButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val matchWatcher = object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable) {
                val matches = views.exportDialogEt.text.toString() == views.exportDialogEtConfirm.text.toString()
                exportButton.isEnabled = matches
                views.exportDialogTilConfirm.error = if (matches) null else getString(CommonStrings.passphrase_passphrase_does_not_match)
            }
        }
        views.exportDialogEt.addTextChangedListener(matchWatcher)
        views.exportDialogEtConfirm.addTextChangedListener(matchWatcher)
    }

    private fun exportDatabase(passphrase: String?) {
        pendingPassphrase = passphrase
        val fileName = "voyage-backup-${timestamp()}" + if (passphrase == null) ".zip" else ".zip.enc"
        val mimeType = if (passphrase == null) ZIP_MIME_TYPE else ENCRYPTED_MIME_TYPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && startPicker(databaseExportLauncher, createDocumentIntent(fileName, mimeType))) return
        // No SAF before KitKat: write straight to Downloads (WRITE_EXTERNAL_STORAGE needs no prompt there).
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        runDatabaseExport(
                open = { file.parentFile?.mkdirs(); FileOutputStream(file) },
                onFailure = { file.delete() },
                onSuccess = { appContext.toast(appContext.getString(CommonStrings.settings_backup_database_saved_to, file.path)) },
        )
    }

    private fun runDatabaseExport(open: () -> OutputStream, onFailure: () -> Unit, onSuccess: () -> Unit) {
        if (databaseJob?.isActive == true) return
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        val progress = ImagePackProgressDialog(requireContext(), CommonStrings.settings_backup_database_exporting) { databaseJob?.cancel() }
        databaseJob = lifecycleScope.launch {
            try {
                databaseBackupManager.export(open, passphrase, progressReporter(progress))
                onSuccess()
            } catch (e: CancellationException) {
                onFailure()
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Database export failed")
                onFailure()
                val message = if (e is BackupTooLargeException) CommonStrings.settings_backup_database_too_large else CommonStrings.settings_backup_database_export_failed
                context?.toast(message)
            } finally {
                progress.dismiss()
            }
        }
    }
}
