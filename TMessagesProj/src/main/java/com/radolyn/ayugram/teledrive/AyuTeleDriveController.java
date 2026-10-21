/*
 * TeleDrive Virtual Folder Engine & Cloud Index Sync for AyuGram4A
 * Uses AyuGram's native ConnectionsManager, MessagesController, MessagesStorage, FileLoader, SendMessagesHelper, PhotoViewer & MediaController.
 */

package com.radolyn.ayugram.teledrive;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import com.google.android.exoplayer2.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.TeleDriveDao;
import com.radolyn.ayugram.database.entities.TeleDriveFile;
import com.radolyn.ayugram.database.entities.TeleDriveFolder;
import com.radolyn.ayugram.database.entities.TeleDriveWorkspace;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.PhotoViewer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AyuTeleDriveController {
    private static final String TAG = "AyuTeleDrive";
    private static final Pattern INDEX_META_PATTERN = Pattern.compile("content=(-?\\d+)\\s+rev=(\\d+)");
    private static final DispatchQueue driveQueue = new DispatchQueue("AyuTeleDriveQueue");

    private static AyuTeleDriveController instance;
    private final TeleDriveDao dao;
    private final Gson gson;
    private final ConcurrentHashMap<Long, Runnable> pendingPushTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingUploadFolderByFile = new ConcurrentHashMap<>();

    private AyuTeleDriveController() {
        dao = AyuData.getTeleDriveDao();
        gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    public static AyuTeleDriveController getInstance() {
        if (instance == null) {
            instance = new AyuTeleDriveController();
        }
        return instance;
    }

    public TeleDriveDao getDao() {
        return dao;
    }

    // =========================================================================
    // 1. Dialog & Workspace Discovery / Vault Creation
    // =========================================================================

    public List<TeleDriveModels.ChatSummary> listAvailableChats(int accountId) {
        var controller = MessagesController.getInstance(accountId);
        long myId = UserConfig.getInstance(accountId).getClientUserId();
        var summaries = new ArrayList<TeleDriveModels.ChatSummary>();
        var seen = new HashSet<Long>();

        // Always include Saved Messages at the top
        summaries.add(new TeleDriveModels.ChatSummary(
                myId,
                LocaleController.getString("SavedMessages", R.string.SavedMessages),
                TeleDriveModels.CHAT_KIND_SAVED,
                false,
                true
        ));
        seen.add(myId);

        for (var dialog : controller.getAllDialogs()) {
            if (dialog == null || seen.contains(dialog.id)) {
                continue;
            }
            if (DialogObject.isChatDialog(dialog.id)) {
                TLRPC.Chat chat = controller.getChat(-dialog.id);
                if (chat == null || ChatObject.isNotInChat(chat)) {
                    continue;
                }
                boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
                String kind = isChannel ? TeleDriveModels.CHAT_KIND_CHANNEL : TeleDriveModels.CHAT_KIND_GROUP;
                boolean isVault = TeleDriveModels.DEFAULT_VAULT_TITLE.equalsIgnoreCase(chat.title);
                boolean canPost = canPostToDialog(accountId, dialog.id);
                summaries.add(new TeleDriveModels.ChatSummary(dialog.id, chat.title, kind, isVault, canPost));
                seen.add(dialog.id);
            }
        }

        summaries.sort((a, b) -> {
            if (a.isVault != b.isVault) return a.isVault ? -1 : 1;
            if (!TextUtils.equals(a.kind, b.kind)) {
                if (TeleDriveModels.CHAT_KIND_SAVED.equals(a.kind)) return -1;
                if (TeleDriveModels.CHAT_KIND_SAVED.equals(b.kind)) return 1;
            }
            return a.title.compareToIgnoreCase(b.title);
        });
        return summaries;
    }

    public boolean canPostToDialog(int accountId, long dialogId) {
        long myId = UserConfig.getInstance(accountId).getClientUserId();
        if (dialogId == myId || dialogId > 0) {
            return true;
        }
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
        if (chat == null) {
            return false;
        }
        return ChatObject.canSendDocument(chat) || ChatObject.canPost(chat) || chat.creator;
    }

    public String getDialogTitle(int accountId, long dialogId) {
        long myId = UserConfig.getInstance(accountId).getClientUserId();
        if (dialogId == myId) {
            return LocaleController.getString("SavedMessages", R.string.SavedMessages);
        }
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        } else {
            TLRPC.User user = MessagesController.getInstance(accountId).getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        }
        return "Chat " + dialogId;
    }

    public String getDialogKind(int accountId, long dialogId) {
        long myId = UserConfig.getInstance(accountId).getClientUserId();
        if (dialogId == myId) {
            return TeleDriveModels.CHAT_KIND_SAVED;
        }
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
            if (chat != null && ChatObject.isChannel(chat) && !chat.megagroup) {
                return TeleDriveModels.CHAT_KIND_CHANNEL;
            }
        }
        return TeleDriveModels.CHAT_KIND_GROUP;
    }

    public TeleDriveWorkspace getOrCreateWorkspace(int accountId, long dialogId) {
        TeleDriveWorkspace ws = dao.getWorkspace(dialogId);
        boolean canUpload = canPostToDialog(accountId, dialogId);
        String title = getDialogTitle(accountId, dialogId);
        if (ws == null) {
            ws = new TeleDriveWorkspace();
            ws.chatId = dialogId;
            ws.title = title;
            ws.kind = getDialogKind(accountId, dialogId);
            ws.canUpload = canUpload;
            ws.indexMode = canUpload ? TeleDriveModels.INDEX_MODE_CLOUD : TeleDriveModels.INDEX_MODE_LOCAL;
            ws.indexChatId = canUpload ? dialogId : 0L;
            ws.indexMessageId = 0;
            ws.indexRevision = 0L;
            ws.localSeq = 0L;
            ws.syncedSeq = 0L;
            ws.newestScannedMessageId = 0;
            ws.lastSyncAt = 0L;
            ws.createdAt = System.currentTimeMillis();
            dao.upsertWorkspace(ws);
        } else {
            dao.setPermissions(dialogId, canUpload, title);
            ws = dao.getWorkspace(dialogId);
        }
        return ws;
    }

    public void createVaultChannel(int accountId, String rawTitle, Consumer<Long> onCreated) {
        String title = TextUtils.isEmpty(rawTitle) ? TeleDriveModels.DEFAULT_VAULT_TITLE : rawTitle.trim();
        TLRPC.TL_channels_createChannel req = new TLRPC.TL_channels_createChannel();
        req.title = title;
        req.about = "TeleDrive storage vault. Files live here; folders are described by index.json.";
        req.broadcast = true;
        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof TLRPC.Updates) {
                TLRPC.Updates updates = (TLRPC.Updates) response;
                MessagesController.getInstance(accountId).processUpdates(updates, false);
                if (!updates.chats.isEmpty()) {
                    long dialogId = -updates.chats.get(0).id;
                    AndroidUtilities.runOnUIThread(() -> {
                        getOrCreateWorkspace(accountId, dialogId);
                        if (onCreated != null) {
                            onCreated.accept(dialogId);
                        }
                    });
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (onCreated != null) {
                    onCreated.accept(0L);
                }
            });
        });
    }

    // =========================================================================
    // 2. Virtual Folder & File Management (Room VFS + Debounced Cloud Index Sync)
    // =========================================================================

    public TeleDriveFolder createFolder(int accountId, long chatId, String parentId, String rawName) {
        getOrCreateWorkspace(accountId, chatId);
        String clean = cleanName(rawName);
        String unique = uniqueFolderName(chatId, parentId, clean);
        long now = System.currentTimeMillis();

        TeleDriveFolder folder = new TeleDriveFolder();
        folder.id = UUID.randomUUID().toString();
        folder.workspaceChatId = chatId;
        folder.parentId = TextUtils.isEmpty(parentId) ? TeleDriveModels.ROOT_ID : parentId;
        folder.name = unique;
        folder.createdAt = now;
        folder.updatedAt = now;

        dao.upsertFolder(folder);
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
        return folder;
    }

    public void renameFolder(int accountId, long chatId, String folderId, String rawName) {
        TeleDriveFolder current = dao.getFolder(folderId);
        if (current == null) {
            return;
        }
        String clean = cleanName(rawName);
        if (clean.equals(current.name)) {
            return;
        }
        String unique = uniqueFolderName(chatId, current.parentId, clean);
        dao.renameFolder(folderId, unique, System.currentTimeMillis());
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    /** Deleting a folder never deletes files: its contents move up to parentId. */
    public void deleteFolder(int accountId, long chatId, String folderId) {
        TeleDriveFolder folder = dao.getFolder(folderId);
        if (folder == null) {
            return;
        }
        String parentId = TextUtils.isEmpty(folder.parentId) ? TeleDriveModels.ROOT_ID : folder.parentId;
        long now = System.currentTimeMillis();
        dao.reparentFiles(chatId, folderId, parentId);
        dao.reparentChildFolders(chatId, folderId, parentId, now);
        dao.deleteFolder(folderId);
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    public void moveFiles(int accountId, long chatId, List<Integer> messageIds, String targetFolderId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        dao.moveFiles(chatId, messageIds, targetFolderId, now);
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    public void addOrMoveMessageToFolder(int accountId, long chatId, TLRPC.Message msg, String targetFolderId) {
        if (msg == null) {
            return;
        }
        getOrCreateWorkspace(accountId, chatId);
        long now = System.currentTimeMillis();
        TeleDriveFile existing = dao.getFile(chatId, msg.id);
        if (existing == null) {
            TeleDriveFile mapped = mapMessageToDriveFile(accountId, chatId, msg, targetFolderId, now);
            if (mapped != null) {
                mapped.customized = !TeleDriveModels.ROOT_ID.equals(targetFolderId);
                dao.upsertFile(mapped);
            }
        } else {
            dao.moveFiles(chatId, Collections.singletonList(msg.id), targetFolderId, now);
        }
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    public void renameFile(int accountId, long chatId, int messageId, String rawName) {
        String clean = cleanName(rawName);
        dao.renameFile(chatId, messageId, clean, System.currentTimeMillis());
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    public void deleteFiles(int accountId, long chatId, List<Integer> messageIds, boolean deleteFromTelegram) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (deleteFromTelegram && canPostToDialog(accountId, chatId)) {
            ArrayList<Integer> idsList = new ArrayList<>(messageIds);
            MessagesController.getInstance(accountId).deleteMessages(idsList, null, null, chatId, true, false);
            dao.deleteFiles(chatId, messageIds);
        } else {
            dao.setFilesHidden(chatId, messageIds, true, now);
        }
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    // =========================================================================
    // 3. Channel Scanner & Message-to-DriveFile Mapper
    // =========================================================================

    public void syncWorkspace(int accountId, long chatId, boolean fullRescan, Runnable onUiUpdate) {
        driveQueue.postRunnable(() -> {
            try {
                TeleDriveWorkspace ws = getOrCreateWorkspace(accountId, chatId);
                scanDialogHistory(accountId, ws, fullRescan, onUiUpdate);
            } catch (Exception e) {
                Log.e(TAG, "syncWorkspace failed", e);
            }
        });
    }

    private void scanDialogHistory(int accountId, TeleDriveWorkspace ws, boolean fullRescan, Runnable onUiUpdate) {
        long chatId = ws.chatId;
        int stopAt = fullRescan ? 0 : ws.newestScannedMessageId;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(accountId).getInputPeer(chatId);
        req.limit = 100;
        req.offset_id = 0;

        scanNextBatch(accountId, ws, req, stopAt, fullRescan, 0, new ArrayList<>(), onUiUpdate);
    }

    private void scanNextBatch(
            int accountId,
            TeleDriveWorkspace ws,
            TLRPC.TL_messages_getHistory req,
            int stopAt,
            boolean fullRescan,
            int pageCount,
            List<Integer> seenIds,
            Runnable onUiUpdate
    ) {
        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> driveQueue.postRunnable(() -> {
            long chatId = ws.chatId;
            long now = System.currentTimeMillis();
            int newest = ws.newestScannedMessageId;
            TLRPC.Message indexCandidate = null;
            boolean reachedStop = false;

            if (error == null && response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(accountId).putUsers(res.users, false);
                MessagesController.getInstance(accountId).putChats(res.chats, false);

                if (res.messages == null || res.messages.isEmpty()) {
                    reachedStop = true;
                } else {
                    List<TeleDriveFile> batch = new ArrayList<>();
                    for (TLRPC.Message m : res.messages) {
                        if (m == null) continue;
                        if (!fullRescan && m.id <= stopAt) {
                            reachedStop = true;
                            break;
                        }
                        if (m.id > newest) {
                            newest = m.id;
                        }
                        if (isIndexMessage(m)) {
                            if (indexCandidate == null) {
                                indexCandidate = m;
                            }
                            continue;
                        }
                        TeleDriveFile df = mapMessageToDriveFile(accountId, chatId, m, TeleDriveModels.ROOT_ID, now);
                        if (df != null) {
                            batch.add(df);
                            seenIds.add(m.id);
                        }
                    }

                    if (!batch.isEmpty()) {
                        dao.insertFilesIgnore(batch);
                    }
                    req.offset_id = res.messages.get(res.messages.size() - 1).id;
                    if (res.messages.size() < 50 || pageCount >= 15) {
                        reachedStop = true;
                    }
                }
            } else {
                reachedStop = true;
            }

            if (indexCandidate != null) {
                adoptIndexCandidate(accountId, chatId, indexCandidate, onUiUpdate);
            }

            if (newest > ws.newestScannedMessageId) {
                dao.setScanCursor(chatId, newest, now);
                ws.newestScannedMessageId = newest;
            }

            if (onUiUpdate != null) {
                AndroidUtilities.runOnUIThread(onUiUpdate);
            }

            if (!reachedStop) {
                scanNextBatch(accountId, ws, req, stopAt, fullRescan, pageCount + 1, seenIds, onUiUpdate);
            } else {
                TeleDriveWorkspace freshWs = dao.getWorkspace(chatId);
                if (freshWs != null) {
                    pullAndMergeIndex(accountId, freshWs, () -> {
                        if (freshWs.needsPush()) {
                            pushIndexNow(accountId, chatId);
                        }
                        if (onUiUpdate != null) {
                            AndroidUtilities.runOnUIThread(onUiUpdate);
                        }
                    });
                }
            }
        }));
    }

    public static boolean isIndexMessage(TLRPC.Message msg) {
        if (msg == null || !(msg.media instanceof TLRPC.TL_messageMediaDocument) || msg.media.document == null) {
            return false;
        }
        String fileName = FileLoader.getDocumentFileName(msg.media.document);
        return TeleDriveModels.INDEX_FILE_NAME.equalsIgnoreCase(fileName)
                && msg.message != null
                && msg.message.startsWith(TeleDriveModels.INDEX_CAPTION_TAG);
    }

    public static long[] parseIndexMeta(TLRPC.Message msg) {
        if (msg == null || TextUtils.isEmpty(msg.message)) {
            return null;
        }
        Matcher m = INDEX_META_PATTERN.matcher(msg.message);
        if (m.find()) {
            try {
                long contentChat = Long.parseLong(m.group(1));
                long rev = Long.parseLong(m.group(2));
                return new long[]{contentChat, rev};
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public TeleDriveFile mapMessageToDriveFile(int accountId, long chatId, TLRPC.Message msg, String folderId, long now) {
        if (msg == null || msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty || isIndexMessage(msg)) {
            return null;
        }
        if (MessageObject.isStickerMessage(msg) || MessageObject.isAnimatedStickerMessage(msg)) {
            return null;
        }

        TeleDriveFile df = new TeleDriveFile();
        df.chatId = chatId;
        df.messageId = msg.id;
        df.folderId = TextUtils.isEmpty(folderId) ? TeleDriveModels.ROOT_ID : folderId;
        df.date = msg.date;
        df.updatedAt = now;

        File existingPath = FileLoader.getInstance(accountId).getPathToMessage(msg);
        if (existingPath != null && existingPath.exists()) {
            df.localPath = existingPath.getAbsolutePath();
        }

        if (msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null && !(msg.media.photo instanceof TLRPC.TL_photoEmpty)) {
            TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(msg.media.photo.sizes, AndroidUtilities.getPhotoSize());
            df.name = "photo_" + msg.id + ".jpg";
            df.mimeType = "image/jpeg";
            df.kind = TeleDriveModels.FILE_KIND_PHOTO;
            if (best != null) {
                df.size = best.size;
                df.width = best.w;
                df.height = best.h;
            }
            return df;
        } else if (msg.media instanceof TLRPC.TL_messageMediaDocument && msg.media.document != null && !(msg.media.document instanceof TLRPC.TL_documentEmpty)) {
            TLRPC.Document doc = msg.media.document;
            String docName = FileLoader.getDocumentFileName(doc);
            df.size = doc.size;
            df.mimeType = doc.mime_type;

            if (MessageObject.isVoiceMessage(msg)) {
                df.kind = TeleDriveModels.FILE_KIND_VOICE;
                df.name = TextUtils.isEmpty(docName) ? "voice_" + msg.id + ".ogg" : docName;
            } else if (MessageObject.isMusicMessage(msg)) {
                df.kind = TeleDriveModels.FILE_KIND_AUDIO;
                df.name = TextUtils.isEmpty(docName) ? "audio_" + msg.id + ".mp3" : docName;
            } else if (MessageObject.isVideoMessage(msg) || MessageObject.isRoundVideoMessage(msg)) {
                df.kind = TeleDriveModels.FILE_KIND_VIDEO;
                df.name = TextUtils.isEmpty(docName) ? "video_" + msg.id + ".mp4" : docName;
            } else if (MessageObject.isGifMessage(msg) || MessageObject.isNewGifMessage(msg)) {
                df.kind = TeleDriveModels.FILE_KIND_ANIMATION;
                df.name = TextUtils.isEmpty(docName) ? "animation_" + msg.id + ".mp4" : docName;
            } else {
                df.kind = TeleDriveModels.FILE_KIND_DOCUMENT;
                df.name = TextUtils.isEmpty(docName) ? "file_" + msg.id : docName;
            }

            if (!TextUtils.isEmpty(msg.attachPath)) {
                String attachName = new File(msg.attachPath).getName();
                String mappedFolder = pendingUploadFolderByFile.remove(attachName);
                if (mappedFolder != null) {
                    df.folderId = mappedFolder;
                    df.customized = !TeleDriveModels.ROOT_ID.equals(mappedFolder);
                }
            }
            return df;
        }
        return null;
    }

    // =========================================================================
    // 4. Cloud index.json Pull, Merge, Push, Split-Channel & Export/Import
    // =========================================================================

    private void adoptIndexCandidate(int accountId, long chatId, TLRPC.Message message, Runnable onUiUpdate) {
        TeleDriveWorkspace ws = dao.getWorkspace(chatId);
        if (ws == null) return;
        long[] meta = parseIndexMeta(message);
        if (meta != null && meta[0] != chatId) {
            return;
        }
        if (TeleDriveModels.INDEX_MODE_CLOUD.equals(ws.indexMode) && ws.indexChatId == chatId) {
            if (ws.indexMessageId == 0 || message.id > ws.indexMessageId) {
                dao.setWorkspaceIndex(chatId, TeleDriveModels.INDEX_MODE_CLOUD, chatId, message.id, ws.indexRevision);
            }
        } else if (TeleDriveModels.INDEX_MODE_LOCAL.equals(ws.indexMode) && ws.localSeq == 0L && ws.indexRevision == 0L) {
            downloadAndApplyIndexMessage(accountId, chatId, message, true, onUiUpdate);
        }
    }

    public void pullAndMergeIndex(int accountId, TeleDriveWorkspace ws, Runnable onComplete) {
        if (ws == null || !TeleDriveModels.INDEX_MODE_CLOUD.equals(ws.indexMode) || ws.indexChatId == 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        long indexChatId = ws.indexChatId;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(accountId).getInputPeer(indexChatId);
        req.limit = 50;
        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> driveQueue.postRunnable(() -> {
            TLRPC.Message latestIndexMsg = null;
            if (error == null && response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (TLRPC.Message m : res.messages) {
                    if (isIndexMessage(m)) {
                        long[] meta = parseIndexMeta(m);
                        if (meta == null || meta[0] == ws.chatId) {
                            latestIndexMsg = m;
                            break;
                        }
                    }
                }
            }
            if (latestIndexMsg == null) {
                if (onComplete != null) onComplete.run();
                return;
            }
            long[] meta = parseIndexMeta(latestIndexMsg);
            long remoteRev = meta != null ? meta[1] : 1L;
            if (latestIndexMsg.id != ws.indexMessageId) {
                dao.setWorkspaceIndex(ws.chatId, TeleDriveModels.INDEX_MODE_CLOUD, indexChatId, latestIndexMsg.id, ws.indexRevision);
            }
            if (remoteRev <= ws.indexRevision) {
                if (onComplete != null) onComplete.run();
                return;
            }
            if (ws.needsPush()) {
                dao.setWorkspaceIndex(ws.chatId, TeleDriveModels.INDEX_MODE_CLOUD, indexChatId, latestIndexMsg.id, remoteRev);
                if (onComplete != null) onComplete.run();
            } else {
                downloadAndApplyIndexMessage(accountId, ws.chatId, latestIndexMsg, true, onComplete);
            }
        }));
    }

    private void downloadAndApplyIndexMessage(int accountId, long chatId, TLRPC.Message indexMsg, boolean replace, Runnable onComplete) {
        File localFile = FileLoader.getInstance(accountId).getPathToMessage(indexMsg);
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            parseAndApplyIndexFile(chatId, indexMsg, localFile, replace, onComplete);
            return;
        }
        if (indexMsg.media == null || indexMsg.media.document == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        TLRPC.Document doc = indexMsg.media.document;
        TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
        TLRPC.TL_inputDocumentFileLocation loc = new TLRPC.TL_inputDocumentFileLocation();
        loc.id = doc.id;
        loc.access_hash = doc.access_hash;
        loc.file_reference = doc.file_reference != null ? doc.file_reference : new byte[0];
        loc.thumb_size = "";
        req.location = loc;
        req.offset = 0;
        req.limit = 512 * 1024;

        ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> driveQueue.postRunnable(() -> {
            if (error == null && response instanceof TLRPC.TL_upload_file) {
                TLRPC.TL_upload_file uploadFile = (TLRPC.TL_upload_file) response;
                if (uploadFile.bytes != null) {
                    try {
                        uploadFile.bytes.rewind();
                        byte[] raw = new byte[uploadFile.bytes.limit()];
                        uploadFile.bytes.buffer.get(raw);
                        uploadFile.bytes.reuse();
                        String jsonText = new String(raw, StandardCharsets.UTF_8);
                        TeleDriveModels.IndexDocument docObj = gson.fromJson(jsonText, TeleDriveModels.IndexDocument.class);
                        if (docObj != null) {
                            applyIndexDocument(chatId, docObj, replace);
                            TeleDriveWorkspace ws = dao.getWorkspace(chatId);
                            if (ws != null) {
                                dao.setWorkspaceIndex(chatId, ws.indexMode, ws.indexChatId, indexMsg.id, Math.max(ws.indexRevision, docObj.revision));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse downloaded index.json", e);
                    }
                }
            }
            if (onComplete != null) onComplete.run();
        }));
    }

    private void parseAndApplyIndexFile(long chatId, TLRPC.Message indexMsg, File file, boolean replace, Runnable onComplete) {
        try {
            String jsonText = readFileText(file);
            TeleDriveModels.IndexDocument docObj = gson.fromJson(jsonText, TeleDriveModels.IndexDocument.class);
            if (docObj != null) {
                applyIndexDocument(chatId, docObj, replace);
                TeleDriveWorkspace ws = dao.getWorkspace(chatId);
                if (ws != null) {
                    dao.setWorkspaceIndex(chatId, ws.indexMode, ws.indexChatId, indexMsg.id, Math.max(ws.indexRevision, docObj.revision));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseAndApplyIndexFile failed", e);
        }
        if (onComplete != null) onComplete.run();
    }

    public void applyIndexDocument(long chatId, TeleDriveModels.IndexDocument doc, boolean replace) {
        if (doc == null) return;
        long now = System.currentTimeMillis();
        Map<String, TeleDriveFolder> existingMap = new HashMap<>();
        if (!replace) {
            for (TeleDriveFolder f : dao.getAllFolders(chatId)) {
                existingMap.put(f.id, f);
            }
        } else {
            dao.deleteAllFolders(chatId);
        }

        Set<String> knownIds = new HashSet<>();
        knownIds.add(TeleDriveModels.ROOT_ID);
        knownIds.addAll(existingMap.keySet());
        if (doc.folders != null) {
            for (TeleDriveModels.IndexFolder f : doc.folders) {
                if (f != null && !TextUtils.isEmpty(f.id) && !TeleDriveModels.ROOT_ID.equals(f.id)) {
                    knownIds.add(f.id);
                }
            }
            List<TeleDriveFolder> inserts = new ArrayList<>();
            for (TeleDriveModels.IndexFolder f : doc.folders) {
                if (f == null || TextUtils.isEmpty(f.id) || TeleDriveModels.ROOT_ID.equals(f.id) || existingMap.containsKey(f.id)) {
                    continue;
                }
                TeleDriveFolder vf = new TeleDriveFolder();
                vf.id = f.id;
                vf.workspaceChatId = chatId;
                vf.parentId = (f.parentId != null && knownIds.contains(f.parentId)) ? f.parentId : TeleDriveModels.ROOT_ID;
                vf.name = f.name;
                vf.createdAt = f.createdAt > 0 ? f.createdAt : now;
                vf.updatedAt = f.updatedAt > 0 ? f.updatedAt : now;
                inserts.add(vf);
            }
            if (!inserts.isEmpty()) {
                dao.upsertFolders(inserts);
            }
        }

        Set<Integer> presentIds = new HashSet<>(dao.getAllFileIds(chatId));
        if (doc.files != null) {
            for (TeleDriveModels.IndexFile entry : doc.files) {
                if (entry == null) continue;
                int mid = (int) entry.messageId;
                String folder = (entry.folderId != null && knownIds.contains(entry.folderId)) ? entry.folderId : TeleDriveModels.ROOT_ID;
                if (presentIds.contains(mid)) {
                    dao.assignFile(chatId, mid, folder, entry.name, now);
                } else {
                    TeleDriveFile nf = new TeleDriveFile();
                    nf.chatId = chatId;
                    nf.messageId = mid;
                    nf.folderId = folder;
                    nf.name = entry.name;
                    nf.size = entry.size;
                    nf.mimeType = entry.mime;
                    nf.kind = entry.kind != null ? entry.kind : TeleDriveModels.FILE_KIND_DOCUMENT;
                    nf.date = entry.date;
                    nf.customized = true;
                    nf.updatedAt = entry.updatedAt > 0 ? entry.updatedAt : now;
                    dao.upsertFile(nf);
                }
            }
        }
        dao.fixOrphanFiles(chatId);
    }

    public void requestIndexPush(int accountId, long chatId) {
        Runnable existing = pendingPushTasks.remove(chatId);
        if (existing != null) {
            driveQueue.cancelRunnable(existing);
        }
        Runnable task = () -> {
            pendingPushTasks.remove(chatId);
            pushIndexNow(accountId, chatId);
        };
        pendingPushTasks.put(chatId, task);
        driveQueue.postRunnable(task, 1500);
    }

    public void pushIndexNow(int accountId, long chatId) {
        driveQueue.postRunnable(() -> {
            TeleDriveWorkspace ws = dao.getWorkspace(chatId);
            if (ws == null || !ws.needsPush()) {
                return;
            }
            if (!TeleDriveModels.INDEX_MODE_CLOUD.equals(ws.indexMode) || ws.indexChatId == 0) {
                dao.setWorkspaceSynced(chatId, ws.localSeq);
                return;
            }
            if (!canPostToDialog(accountId, ws.indexChatId)) {
                dao.setWorkspaceIndex(chatId, TeleDriveModels.INDEX_MODE_LOCAL, 0L, 0, ws.indexRevision);
                dao.setWorkspaceSynced(chatId, ws.localSeq);
                return;
            }

            long seq = ws.localSeq;
            long revision = ws.indexRevision + 1;
            TeleDriveModels.IndexDocument doc = buildIndexDocument(ws, revision);
            String json = gson.toJson(doc);

            File stagingDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "teledrive_staging/" + revision);
            stagingDir.mkdirs();
            File indexFile = new File(stagingDir, TeleDriveModels.INDEX_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(indexFile)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Failed to write staging index.json", e);
                return;
            }

            String caption = TeleDriveModels.INDEX_CAPTION_TAG + " content=" + ws.chatId + " rev=" + revision;
            int oldIndexMsgId = ws.indexMessageId;
            long indexChatId = ws.indexChatId;

            AndroidUtilities.runOnUIThread(() -> {
                SendMessagesHelper.prepareSendingDocument(
                        AccountInstance.getInstance(accountId),
                        indexFile.getAbsolutePath(),
                        indexFile.getAbsolutePath(),
                        null,
                        caption,
                        "application/json",
                        indexChatId,
                        null,
                        null,
                        null,
                        null,
                        true,
                        0
                );
                dao.setWorkspaceIndex(chatId, TeleDriveModels.INDEX_MODE_CLOUD, indexChatId, oldIndexMsgId, revision);
                dao.setWorkspaceSynced(chatId, seq);
                if (oldIndexMsgId > 0) {
                    ArrayList<Integer> delIds = new ArrayList<>();
                    delIds.add(oldIndexMsgId);
                    MessagesController.getInstance(accountId).deleteMessages(delIds, null, null, indexChatId, true, false);
                }
            });
        });
    }

    public TeleDriveModels.IndexDocument buildIndexDocument(TeleDriveWorkspace ws, long revision) {
        TeleDriveModels.IndexDocument doc = new TeleDriveModels.IndexDocument();
        doc.contentChatId = ws.chatId;
        doc.contentTitle = ws.title != null ? ws.title : "";
        doc.revision = revision;
        doc.updatedAt = System.currentTimeMillis();

        for (TeleDriveFolder f : dao.getAllFolders(ws.chatId)) {
            doc.folders.add(new TeleDriveModels.IndexFolder(f.id, f.parentId, f.name, f.createdAt, f.updatedAt));
        }
        for (TeleDriveFile file : dao.getAllFiles(ws.chatId)) {
            if (!file.isHidden && (file.customized || !TeleDriveModels.ROOT_ID.equals(file.folderId))) {
                doc.files.add(new TeleDriveModels.IndexFile(
                        file.messageId,
                        file.folderId,
                        file.name,
                        file.size,
                        file.mimeType,
                        file.kind,
                        file.date,
                        file.updatedAt
                ));
            }
        }
        return doc;
    }

    public void setIndexChannel(int accountId, long chatId, long newIndexChatId) {
        TeleDriveWorkspace ws = getOrCreateWorkspace(accountId, chatId);
        dao.setWorkspaceIndex(chatId, TeleDriveModels.INDEX_MODE_CLOUD, newIndexChatId, 0, ws.indexRevision);
        dao.bumpWorkspace(chatId);
        pushIndexNow(accountId, chatId);
    }

    public void setLocalOnlyMode(long chatId) {
        TeleDriveWorkspace ws = dao.getWorkspace(chatId);
        if (ws == null) return;
        dao.setWorkspaceIndex(chatId, TeleDriveModels.INDEX_MODE_LOCAL, 0L, 0, ws.indexRevision);
        dao.setWorkspaceSynced(chatId, ws.localSeq);
    }

    public File exportIndexToFile(long chatId) throws Exception {
        TeleDriveWorkspace ws = dao.getWorkspace(chatId);
        if (ws == null) {
            throw new IllegalStateException("Workspace not initialized");
        }
        TeleDriveModels.IndexDocument doc = buildIndexDocument(ws, ws.indexRevision + 1);
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "teledrive_shared");
        dir.mkdirs();
        String safeTitle = (ws.title != null ? ws.title : "vault").replaceAll("[^A-Za-z0-9._-]+", "_");
        File out = new File(dir, "teledrive_index_" + safeTitle + ".json");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(gson.toJson(doc).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    public void importIndexFromFile(int accountId, long chatId, File jsonFile) throws Exception {
        String text = readFileText(jsonFile);
        TeleDriveModels.IndexDocument doc = gson.fromJson(text, TeleDriveModels.IndexDocument.class);
        if (doc == null || !"TeleDrive".equals(doc.app)) {
            throw new IllegalArgumentException("Invalid TeleDrive index.json file");
        }
        applyIndexDocument(chatId, doc, false);
        dao.bumpWorkspace(chatId);
        requestIndexPush(accountId, chatId);
    }

    // =========================================================================
    // 5. Upload Files to Folder & Open/Download with Native AyuGram Viewers
    // =========================================================================

    public void uploadFilesToFolder(int accountId, long chatId, String folderId, ArrayList<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        driveQueue.postRunnable(() -> {
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                File f = new File(path);
                if (!f.exists()) continue;
                pendingUploadFolderByFile.put(f.getName(), folderId);
                AndroidUtilities.runOnUIThread(() -> {
                    SendMessagesHelper.prepareSendingDocument(
                            AccountInstance.getInstance(accountId),
                            path,
                            path,
                            null,
                            null,
                            null,
                            chatId,
                            null,
                            null,
                            null,
                            null,
                            true,
                            0
                    );
                });
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                }
            }
        });
    }

    public void openOrDownloadFile(BaseFragment fragment, int accountId, TeleDriveFile driveFile, Runnable onStateChanged) {
        if (fragment == null || fragment.getParentActivity() == null || driveFile == null) {
            return;
        }
        resolveMessageObject(accountId, driveFile.chatId, driveFile.messageId, msgObj -> {
            if (msgObj == null) {
                BulletinFactory.of(fragment).createErrorBulletin("Message not found in chat").show();
                return;
            }
            Activity activity = fragment.getParentActivity();
            if (msgObj.isPhoto() || msgObj.isVideo() || msgObj.isGif() || msgObj.isRoundVideo()) {
                PhotoViewer.getInstance().setParentActivity(fragment);
                ArrayList<MessageObject> list = new ArrayList<>();
                list.add(msgObj);
                PhotoViewer.getInstance().openPhoto(list, 0, driveFile.chatId, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
                return;
            }
            if (msgObj.isVoice() || msgObj.isMusic()) {
                MediaController.getInstance().playMessage(msgObj);
                return;
            }

            File pathFile = FileLoader.getInstance(accountId).getPathToMessage(msgObj.messageOwner);
            if (pathFile != null && pathFile.exists() && pathFile.length() > 0) {
                dao.setLocalPath(driveFile.chatId, driveFile.messageId, pathFile.getAbsolutePath());
                AndroidUtilities.openForView(msgObj, activity, null);
            } else if (msgObj.getDocument() != null) {
                FileLoader.getInstance(accountId).loadFile(msgObj.getDocument(), msgObj, FileLoader.PRIORITY_HIGH, 0);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Downloading " + driveFile.name + " via AyuGram cache...").show();
                if (onStateChanged != null) {
                    onStateChanged.run();
                }
            }
        });
    }

    public void exportFileToDownloads(BaseFragment fragment, int accountId, TeleDriveFile driveFile) {
        resolveMessageObject(accountId, driveFile.chatId, driveFile.messageId, msgObj -> {
            if (msgObj == null || fragment.getParentActivity() == null) {
                return;
            }
            File cached = FileLoader.getInstance(accountId).getPathToMessage(msgObj.messageOwner);
            if (cached != null && cached.exists() && cached.length() > 0) {
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TeleDrive");
                exportDir.mkdirs();
                File target = new File(exportDir, sanitizeFileName(driveFile.name));
                if (AyuUtils.moveOrCopyFile(cached, target)) {
                    dao.setLocalPath(driveFile.chatId, driveFile.messageId, target.getAbsolutePath());
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Saved to Downloads/TeleDrive/" + target.getName()).show();
                }
            } else if (msgObj.getDocument() != null) {
                FileLoader.getInstance(accountId).loadFile(msgObj.getDocument(), msgObj, FileLoader.PRIORITY_HIGH, 0);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Downloading " + driveFile.name + " first...").show();
            }
        });
    }

    public void resolveMessageObject(int accountId, long chatId, int messageId, Consumer<MessageObject> callback) {
        driveQueue.postRunnable(() -> {
            TLRPC.Message localMsg = MessagesStorage.getInstance(accountId).getMessage(chatId, messageId);
            if (localMsg != null) {
                MessageObject obj = new MessageObject(accountId, localMsg, false, true);
                AndroidUtilities.runOnUIThread(() -> callback.accept(obj));
                return;
            }
            if (chatId < 0 && ChatObject.isChannel(-chatId, accountId)) {
                TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
                req.channel = MessagesController.getInstance(accountId).getInputChannel(-chatId);
                req.id.add(messageId);
                ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> {
                    if (error == null && response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        if (!res.messages.isEmpty() && !(res.messages.get(0) instanceof TLRPC.TL_messageEmpty)) {
                            TLRPC.Message m = res.messages.get(0);
                            m.dialog_id = chatId;
                            MessageObject obj = new MessageObject(accountId, m, false, true);
                            AndroidUtilities.runOnUIThread(() -> callback.accept(obj));
                            return;
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> callback.accept(null));
                });
            } else {
                TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
                req.id.add(messageId);
                ConnectionsManager.getInstance(accountId).sendRequest(req, (response, error) -> {
                    if (error == null && response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        if (!res.messages.isEmpty() && !(res.messages.get(0) instanceof TLRPC.TL_messageEmpty)) {
                            TLRPC.Message m = res.messages.get(0);
                            m.dialog_id = chatId;
                            MessageObject obj = new MessageObject(accountId, m, false, true);
                            AndroidUtilities.runOnUIThread(() -> callback.accept(obj));
                            return;
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> callback.accept(null));
                });
            }
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String cleanName(String raw) {
        if (TextUtils.isEmpty(raw)) return "Untitled";
        String cleaned = raw.replace('/', '-').replace('\\', '-').trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        return cleaned.isEmpty() ? "Untitled" : cleaned;
    }

    public static String sanitizeFileName(String raw) {
        if (TextUtils.isEmpty(raw)) return "file";
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", "_").trim();
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    private String uniqueFolderName(long chatId, String parentId, String wanted) {
        Set<String> taken = new HashSet<>();
        for (TeleDriveFolder f : dao.getAllFolders(chatId)) {
            if (TextUtils.equals(f.parentId, parentId) && f.name != null) {
                taken.add(f.name.toLowerCase(Locale.ROOT));
            }
        }
        if (!taken.contains(wanted.toLowerCase(Locale.ROOT))) {
            return wanted;
        }
        int n = 2;
        while (taken.contains((wanted + " (" + n + ")").toLowerCase(Locale.ROOT))) {
            n++;
        }
        return wanted + " (" + n + ")";
    }

    private static String readFileText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = new FileInputStream(f);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}