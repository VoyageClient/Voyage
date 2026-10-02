/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.app.features.imagepack.telegram.TelegramBotApi
import im.vector.app.features.imagepack.telegram.TelegramLinks
import im.vector.app.features.imagepack.telegram.TelegramPackImporter
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class ImagePackListFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        ImagePackListController.Listener,
        VectorMenuProvider {

    @Inject lateinit var repository: ImagePackRepository
    @Inject lateinit var controller: ImagePackListController
    @Inject lateinit var archiver: ImagePackArchiver
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var telegramBotApi: TelegramBotApi
    @Inject lateinit var telegramImporter: Provider<TelegramPackImporter>

    private val listArgs: ImagePackListArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGenericRecyclerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        views.genericRecyclerView.configureWith(controller)
        // Live + off-main: reflects saves/toggles without re-entering, and won't ANR on large scans.
        repository.listDataLive(listArgs.roomId)
                .onEach { controller.setData(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun getMenuRes() = R.menu.menu_image_pack_list

    override fun handlePrepareMenu(menu: Menu) {
        // The token is app-wide, so it's set from the settings list rather than per room.
        menu.findItem(R.id.imagePackListMenuTelegramToken)?.isVisible =
                listArgs.roomId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.imagePackListMenuTelegramToken -> {
                showTelegramTokenDialog()
                true
            }
            else -> false
        }
    }

    private fun showTelegramTokenDialog() {
        val layout = layoutInflater.inflate(R.layout.dialog_base_edit_text, null)
        val dialogViews = DialogBaseEditTextBinding.bind(layout)
        dialogViews.editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        dialogViews.editText.hint = getString(CommonStrings.image_pack_telegram_token_hint)
        dialogViews.editText.setText(vectorPreferences.telegramBotToken().orEmpty())
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_telegram_token)
                .setMessage(CommonStrings.image_pack_telegram_token_message)
                .setView(layout)
                .setPositiveButton(CommonStrings.action_save, null)
                .setNeutralButton(CommonStrings.image_pack_telegram_token_clear) { _, _ -> vectorPreferences.setTelegramBotToken(null) }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
        // Set after show() so a rejected token keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val token = dialogViews.editText.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                vectorPreferences.setTelegramBotToken(null)
                dialog.dismiss()
                return@setOnClickListener
            }
            button.isEnabled = false
            dialogViews.editText.error = null
            dialog.setMessage(getString(CommonStrings.image_pack_telegram_token_checking))
            lifecycleScope.launch {
                try {
                    telegramBotApi.checkToken(token)
                    vectorPreferences.setTelegramBotToken(token)
                    dialog.dismiss()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    dialog.setMessage(getString(CommonStrings.image_pack_telegram_token_message))
                    dialogViews.editText.error = failure.message ?: getString(CommonStrings.image_pack_telegram_token_invalid)
                    button.isEnabled = true
                }
            }
        }
    }

    override fun onPackClicked(pack: ManagedPack) {
        startActivity(
                ImagePackEditActivity.newIntent(
                        requireContext(),
                        roomId = pack.roomId,
                        stateKey = pack.stateKey ?: "",
                        canEdit = pack.canEdit,
                        displayName = pack.displayName,
                )
        )
    }

    override fun onGlobalToggled(pack: ManagedPack, enabled: Boolean) {
        val roomId = pack.roomId ?: return
        val stateKey = pack.stateKey ?: return
        lifecycleScope.launch {
            // The live flow refreshes the list once the account-data write lands; no manual refresh needed.
            try {
                repository.setPackEnabledGlobally(roomId, stateKey, enabled)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            }
        }
    }

    override fun onCreateAccountPack() {
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = null, stateKey = "", canEdit = true))
    }

    override fun onCreateRoomPack() {
        // A room can hold several packs, each under a distinct state_key.
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = listArgs.roomId, stateKey = UUID.randomUUID().toString(), canEdit = true))
    }

    private val importZipLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = extractPickedUris(result.data)
            if (uris.isNotEmpty()) importZips(uris)
        }
    }

    override fun onImportPack() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        importZipLauncher.launch(intent)
    }

    override fun onImportTelegram() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val layout = layoutInflater.inflate(R.layout.dialog_base_edit_text, null)
        val dialogViews = DialogBaseEditTextBinding.bind(layout)
        dialogViews.editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        dialogViews.editText.hint = getString(CommonStrings.image_pack_telegram_link_hint)
        clipboardText()?.takeIf { TelegramLinks.parseLink(it) != null }?.let {
            dialogViews.editText.setText(it.trim())
            dialogViews.editText.setSelection(dialogViews.editText.text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_import_telegram)
                .setView(layout)
                .setPositiveButton(CommonStrings.image_pack_import) { _, _ ->
                    val setName = TelegramLinks.parseSetName(dialogViews.editText.text)
                    if (setName == null) {
                        requireContext().toast(CommonStrings.image_pack_telegram_link_invalid)
                    } else {
                        importTelegram(setName)
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun clipboardText(): String? = runCatching {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun importTelegram(setName: String) {
        val roomId = listArgs.roomId ?: return
        if (importJob?.isActive == true) return
        val dialog = ImagePackProgressDialog(requireContext(), CommonStrings.image_pack_import_telegram) { importJob?.cancel() }
        importJob = lifecycleScope.launch {
            val result = try {
                telegramImporter.get().import(roomId, setName) { name, done, total ->
                    activity?.runOnUiThread { dialog.update(getString(CommonStrings.image_pack_telegram_downloading, name, done, total), done, total) }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
                null
            } finally {
                dialog.dismiss()
            }
            if (result == null) return@launch
            if (!isAdded) {
                (result as? TelegramPackImporter.Result.Draft)?.let { File(it.draft.dir).deleteRecursively() }
                return@launch
            }
            when (result) {
                is TelegramPackImporter.Result.UpToDate ->
                    requireContext().toast(getString(CommonStrings.image_pack_telegram_up_to_date, result.packName))
                is TelegramPackImporter.Result.Draft -> {
                    startActivity(
                            ImagePackEditActivity.newIntent(
                                    requireContext(),
                                    roomId = roomId,
                                    stateKey = result.stateKey,
                                    canEdit = true,
                                    displayName = result.packName,
                                    draft = result.draft,
                            )
                    )
                    if (result.skipped.isNotEmpty()) {
                        MaterialAlertDialogBuilder(requireContext())
                                .setTitle(CommonStrings.image_pack_import_telegram)
                                .setMessage(getString(CommonStrings.image_pack_telegram_skipped, result.skipped.joinToString(", ")))
                                .setPositiveButton(CommonStrings.ok, null)
                                .show()
                    }
                }
            }
        }
    }

    private var importJob: Job? = null

    // Each zip opens as an unsaved pack; the first selected ends up on top.
    private fun importZips(uris: List<Uri>) {
        val roomId = listArgs.roomId ?: return
        if (importJob?.isActive == true) return
        val dialog = ImagePackProgressDialog(requireContext(), CommonStrings.image_pack_import) { importJob?.cancel() }
        importJob = lifecycleScope.launch {
            val drafts = mutableListOf<PackDraft>()
            var firstFailure: Throwable? = null
            try {
                uris.forEach { uri ->
                    try {
                        drafts += archiver.extractDraft(uri)
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                drafts.forEach { File(it.dir).deleteRecursively() }
                throw cancellation
            } finally {
                dialog.dismiss()
            }
            if (!isAdded) {
                drafts.forEach { File(it.dir).deleteRecursively() }
                return@launch
            }
            firstFailure?.let { showFailure(it) }
            if (drafts.isNotEmpty()) {
                val intents = drafts.reversed().map { draft ->
                    ImagePackEditActivity.newIntent(
                            requireContext(),
                            roomId = roomId,
                            stateKey = UUID.randomUUID().toString(),
                            canEdit = true,
                            displayName = draft.packName,
                            draft = draft,
                    )
                }
                requireActivity().startActivities(intents.toTypedArray())
            }
        }
    }
}
