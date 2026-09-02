package com.veilkeepers.app.vault.attach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.vault.AttachmentMeta
import com.veilkeepers.app.vault.VaultRepository
import com.veilkeepers.app.vault.vaultErrorUiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Attachment screen state (Sprint 8). Kept as one flat data class so the UI
 * recomposes on a single immutable snapshot; [attachments] holds ONLY the
 * decrypted metadata, and [preview] holds the decrypted plaintext bytes of the
 * single attachment the user tapped to open (never the whole list).
 */
data class AttachmentUiState(
    val itemId: Long? = null,
    val attachments: List<AttachmentMeta> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val preview: AttachmentPreview? = null,
    val error: String? = null,
)

/**
 * One decrypted attachment ready to render: the plaintext image bytes plus the
 * server-declared MIME (used only to pick a decoder hint — the bytes themselves
 * are validated by magic number in [com.veilkeepers.app.vault.attach.ImageCompressor]).
 */
class AttachmentPreview(
    val attachmentId: Long,
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * Drives the attachment UI over [VaultRepository] (Sprint 8). Constructed via
 * [factory] with the SAME in-memory VK the vault VM uses, so a locked /
 * zeroized vault can never decrypt attachments either.
 *
 * All plaintext material is transient: uploaded plaintext is zeroized right
 * after the call, and preview bytes are zeroized when the dialog closes or the
 * VM clears. Mutations run under [mutationLock] so an upload/delete can never
 * interleave its re-list with another mutation's publish.
 */
class AttachmentViewModel(private val repository: VaultRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AttachmentUiState())
    val uiState: StateFlow<AttachmentUiState> = _uiState.asStateFlow()

    private val mutationLock = Mutex()

    /** (Re)lists the attachments of [itemId]; clears stale state on item switch. */
    fun refresh(itemId: Long) {
        viewModelScope.launch {
            _uiState.update { previous ->
                if (previous.itemId == itemId) {
                    previous.copy(loading = true, error = null)
                } else {
                    // Different item: drop the old list AND any open preview so
                    // bytes decrypted under the previous item never linger.
                    previous.preview?.bytes?.fill(0)
                    AttachmentUiState(itemId = itemId, loading = true)
                }
            }
            try {
                val list = repository.listAttachments(itemId)
                _uiState.update { it.copy(itemId = itemId, attachments = list, loading = false) }
            } catch (error: Throwable) {
                _uiState.update { it.copy(loading = false, error = vaultErrorUiMessage(error)) }
            }
        }
    }

    /**
     * Encrypts and uploads [plaintext] (already compressed/validated by the
     * caller) under [filename] + [mimeType], then re-lists. The plaintext array
     * is zeroized in a finally block regardless of outcome.
     */
    fun upload(itemId: Long, filename: String, mimeType: String, plaintext: ByteArray) {
        viewModelScope.launch {
            mutationLock.withLock {
                _uiState.update { it.copy(busy = true, error = null) }
                try {
                    repository.uploadAttachment(itemId, filename, mimeType, plaintext)
                    val list = repository.listAttachments(itemId)
                    _uiState.update { it.copy(itemId = itemId, busy = false, attachments = list) }
                } catch (error: Throwable) {
                    _uiState.update { it.copy(busy = false, error = vaultErrorUiMessage(error)) }
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }

    /** Downloads and decrypts one attachment into the preview slot. */
    fun openPreview(itemId: Long, attachmentId: Long, mimeType: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                val bytes = repository.downloadAttachment(itemId, attachmentId)
                _uiState.update {
                    it.preview?.bytes?.fill(0)
                    it.copy(busy = false, preview = AttachmentPreview(attachmentId, bytes, mimeType))
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(busy = false, error = vaultErrorUiMessage(error)) }
            }
        }
    }

    /** Closes the preview dialog and zeroizes its plaintext bytes. */
    fun closePreview() {
        _uiState.update {
            it.preview?.bytes?.fill(0)
            it.copy(preview = null)
        }
    }

    /** DELETEs one attachment (the server also removes its ciphertext file). */
    fun delete(itemId: Long, attachmentId: Long) {
        viewModelScope.launch {
            mutationLock.withLock {
                _uiState.update { it.copy(busy = true, error = null) }
                try {
                    repository.deleteAttachment(itemId, attachmentId)
                    val list = repository.listAttachments(itemId)
                    _uiState.update {
                        if (it.preview?.attachmentId == attachmentId) it.preview?.bytes?.fill(0)
                        it.copy(
                            busy = false,
                            attachments = list,
                            preview = if (it.preview?.attachmentId == attachmentId) null else it.preview,
                        )
                    }
                } catch (error: Throwable) {
                    _uiState.update { it.copy(busy = false, error = vaultErrorUiMessage(error)) }
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        _uiState.value.preview?.bytes?.fill(0)
        super.onCleared()
    }

    companion object {
        /**
         * Wires the in-memory VK + session into a dedicated repository, exactly
         * like [com.veilkeepers.app.vault.VaultViewModel.factory] — the caller
         * keys this VM to the same unlock generation so it is cleared on lock.
         */
        fun factory(vaultKey: ByteArray, storage: SessionStorage): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = VaultRepository(
                        vaultKey = vaultKey,
                        sessionToken = storage.sessionToken,
                        baseUrl = storage.serverUrl,
                        authRepository = AuthRepository(storage),
                    )
                    return AttachmentViewModel(repository) as T
                }
            }
    }
}
