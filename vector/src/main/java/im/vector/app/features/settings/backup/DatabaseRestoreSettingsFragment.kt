/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.imagepack.edit.ImagePackProgressDialog
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date
import java.util.zip.ZipException
import javax.inject.Inject

abstract class DatabaseRestoreSettingsFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var preferencesBackupManager: PreferencesBackupManager
    @Inject lateinit var databaseBackupManager: DatabaseBackupManager

    protected var databaseJob: Job? = null

    private val databaseImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            readDatabaseBackup(uri)
        }
    }

    protected fun bindDatabaseImport() {
        onClick(DATABASE_IMPORT_KEY) { pickFileToImport(databaseImportLauncher, DATABASE_MIME_TYPES) }
    }

    protected fun pickFileToImport(launcher: ActivityResultLauncher<Intent>, mimeTypes: Array<String>) {
        if (!startPicker(launcher, openDocumentIntent(mimeTypes))) requireContext().toast(CommonStrings.error_no_external_application_found)
    }

    protected fun onClick(key: String, action: () -> Unit) {
        findPreference<VectorPreference>(key)?.setOnPreferenceClickListener {
            action()
            true
        }
    }

    protected fun startPicker(launcher: ActivityResultLauncher<Intent>, intent: Intent): Boolean = try {
        launcher.launch(intent)
        true
    } catch (activityNotFound: ActivityNotFoundException) {
        false
    }

    // EXTRA_INITIAL_URI (API 26) opens the picker beside the last backup; older pickers ignore it.
    protected fun withLastLocation(intent: Intent): Intent = intent.apply {
        val last = preferencesBackupManager.lastLocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && last != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, last)
    }

    private fun openDocumentIntent(mimeTypes: Array<String>): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            withLastLocation(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            )
        } else {
            // Pre-KitKat file managers rarely tag .json or .zip.enc usefully, and the contents get validated anyway.
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        }
    }

    private fun readDatabaseBackup(uri: Uri) {
        lifecycleScope.launch {
            val encrypted = try {
                databaseBackupManager.isEncrypted(uri)
            } catch (e: Exception) {
                Timber.e(e, "Database import failed")
                context?.toast(CommonStrings.settings_backup_database_import_failed)
                return@launch
            }
            if (encrypted) askImportPassphrase(uri) else stageDatabaseBackup(uri, passphrase = null)
        }
    }

    private fun askImportPassphrase(uri: Uri) {
        if (!isAdded) return
        val views = DialogBaseEditTextBinding.inflate(layoutInflater)
        views.editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        views.editText.setHint(CommonStrings.passphrase_enter_passphrase)
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_backup_database_passphrase_title)
                .setView(views.root)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val passphrase = views.editText.text.toString()
                    if (passphrase.isEmpty()) askImportPassphrase(uri) else stageDatabaseBackup(uri, passphrase)
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun stageDatabaseBackup(uri: Uri, passphrase: String?) {
        if (databaseJob?.isActive == true || !isAdded) return
        val progress = ImagePackProgressDialog(requireContext(), CommonStrings.settings_backup_database_reading) { databaseJob?.cancel() }
        databaseJob = lifecycleScope.launch {
            try {
                val staged = databaseBackupManager.stage(uri, passphrase, progressReporter(progress))
                preferencesBackupManager.lastLocation = uri
                progress.dismiss()
                confirmRestore(staged)
            } catch (e: WrongPassphraseException) {
                context?.toast(CommonStrings.settings_backup_database_wrong_passphrase)
                askImportPassphrase(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Database import failed")
                val message = if (e is InvalidBackupException || e is CorruptedBackupException || e is ZipException) {
                    CommonStrings.settings_backup_database_invalid
                } else {
                    CommonStrings.settings_backup_database_import_failed
                }
                context?.toast(message)
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun confirmRestore(staged: StagedBackup) {
        val activity = activity
        if (activity == null) {
            databaseBackupManager.discardStaged()
            return
        }
        val date = DateFormat.getDateFormat(activity).format(Date(staged.createdAt)) + " " + DateFormat.getTimeFormat(activity).format(Date(staged.createdAt))
        val accounts = staged.userIds.joinToString(", ").ifEmpty { getString(CommonStrings.settings_backup_database_restore_no_accounts) }
        MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.settings_backup_database_restore_title)
                .setMessage(getString(CommonStrings.settings_backup_database_restore_message, date, accounts))
                .setCancelable(false)
                .setPositiveButton(CommonStrings.settings_backup_database_restore_action) { _, _ ->
                    databaseBackupManager.commitStaged()
                    MainActivity.restartProcess(activity)
                }
                .setNegativeButton(CommonStrings.action_cancel) { _, _ -> databaseBackupManager.discardStaged() }
                .show()
    }

    protected fun progressReporter(progress: ImagePackProgressDialog): (Long, Long) -> Unit {
        var lastStep = -1L
        return { done, total ->
            // Called every few KB from the IO thread; only hop to the UI when the bar or the text would change.
            val step = if (total > 0) done * 1000 / total else done / (1024 * 1024)
            if (step != lastStep) {
                lastStep = step
                activity?.runOnUiThread {
                    val context = context ?: return@runOnUiThread
                    if (total > 0) {
                        val text = getString(
                                CommonStrings.settings_backup_progress,
                                Formatter.formatShortFileSize(context, done),
                                Formatter.formatShortFileSize(context, total),
                        )
                        progress.update(text, step.toInt(), 1000)
                    } else {
                        progress.update(Formatter.formatShortFileSize(context, done), 0, 0)
                    }
                }
            }
        }
    }

    companion object {
        const val DATABASE_IMPORT_KEY = "SETTINGS_BACKUP_DATABASE_IMPORT_KEY"
        const val ZIP_MIME_TYPE = "application/zip"
        const val ENCRYPTED_MIME_TYPE = "application/octet-stream"
        const val JSON_MIME_TYPE = "application/json"

        // An encrypted .zip.enc has no MIME type of its own, so providers report it as octet-stream.
        private val DATABASE_MIME_TYPES = arrayOf(ZIP_MIME_TYPE, "application/x-zip-compressed", ENCRYPTED_MIME_TYPE)
    }
}
