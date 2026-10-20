/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.utils;

import android.text.TextUtils;
import androidx.core.util.Pair;
import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.AyuConfig;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;
import com.radolyn.ayugram.utils.AyuFileLocation;
import org.telegram.messenger.*;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

public class AyuMessageUtils {

    private static boolean shouldSaveMedia(int accountId, long dialogId) {
        if (!AyuConfig.saveMedia) {
            return false;
        }
        long clientUserId = UserConfig.getInstance(accountId).getClientUserId();
        if (dialogId == clientUserId) {
            return true;
        }
        if (dialogId > 0) {
            return AyuConfig.saveMediaInPrivateChats;
        }
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(Math.abs(dialogId));
        if (chat == null) {
            return true;
        }
        boolean isPublic = ChatObject.isPublic(chat);
        if (ChatObject.isChannel(chat) && !chat.megagroup) {
            return isPublic ? AyuConfig.saveMediaInPublicChannels : AyuConfig.saveMediaInPrivateChannels;
        } else {
            return isPublic ? AyuConfig.saveMediaInPublicGroups : AyuConfig.saveMediaInPrivateGroups;
        }
    }

    public static void map(AyuSavePreferences prefs, AyuMessageBase target) {
        var msg = prefs.getMessage();
        if (msg == null) {
            return;
        }

        target.userId = prefs.getUserId();
        target.dialogId = prefs.getDialogId();
        target.groupedId = msg.grouped_id;
        target.peerId = MessageObject.getPeerId(msg.peer_id);
        target.fromId = msg.from_id != null ? MessageObject.getPeerId(msg.from_id) : (msg.out ? prefs.getUserId() : target.peerId);
        target.topicId = prefs.getTopicId();
        target.messageId = msg.id;
        target.date = msg.date;
        target.flags = msg.flags;
        target.editDate = msg.edit_date;
        target.views = msg.views;

        if (msg.fwd_from != null) {
            target.fwdFlags = msg.fwd_from.flags;
            target.fwdFromId = msg.fwd_from.from_id != null ? MessageObject.getPeerId(msg.fwd_from.from_id) : 0;
            target.fwdName = msg.fwd_from.from_name;
            target.fwdDate = msg.fwd_from.date;
            target.fwdPostAuthor = msg.fwd_from.post_author;
        }

        if (msg.reply_to != null) {
            target.replyFlags = msg.reply_to.flags;
            target.replyMessageId = msg.reply_to.reply_to_msg_id;
            target.replyPeerId = msg.reply_to.reply_to_peer_id != null ? MessageObject.getPeerId(msg.reply_to.reply_to_peer_id) : 0;
            target.replyTopId = msg.reply_to.reply_to_top_id;
            target.replyForumTopic = msg.reply_to.forum_topic;
        }

        target.entityCreateDate = prefs.getRequestCatchTime();
        target.text = msg.message;

        if (AyuConfig.saveFormatting && msg.entities != null && !msg.entities.isEmpty()) {
            try {
                int size = 4;
                for (var entity : msg.entities) {
                    size += entity.getObjectSize();
                }
                var buffer = new NativeByteBuffer(size);
                buffer.writeInt32(msg.entities.size());
                for (var entity : msg.entities) {
                    entity.serializeToStream(buffer);
                }
                buffer.rewind();
                byte[] bytes = new byte[buffer.limit()];
                buffer.buffer.get(bytes);
                target.textEntities = bytes;
                buffer.reuse();
            } catch (Exception e) {
                Log.e("AyuGram", "Failed to serialize textEntities", e);
            }
        }
    }

    public static void mapMedia(AyuSavePreferences prefs, AyuMessageBase target, boolean needToSaveMedia) {
        var msg = prefs.getMessage();
        if (msg == null || msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) {
            target.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
            return;
        }

        if (needToSaveMedia && !shouldSaveMedia(prefs.getAccountId(), prefs.getDialogId())) {
            target.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
            return;
        }

        int accountId = prefs.getAccountId();
        var fileLoader = FileLoader.getInstance(accountId);

        try {
            if (msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null && !(msg.media.photo instanceof TLRPC.TL_photoEmpty)) {
                target.documentType = AyuConstants.DOCUMENT_TYPE_PHOTO;
                File attachFile = resolveAttachFile(accountId, msg);
                if (attachFile != null && attachFile.exists()) {
                    if (needToSaveMedia) {
                        File dest = new File(AyuMessagesController.attachmentsPath, AyuUtils.getFilename(msg, attachFile));
                        if (AyuUtils.moveOrCopyFile(attachFile, dest)) {
                            target.mediaPath = dest.getAbsolutePath();
                        } else {
                            target.mediaPath = attachFile.getAbsolutePath();
                        }
                    } else {
                        target.mediaPath = attachFile.getAbsolutePath();
                    }
                }
            } else if (msg.media instanceof TLRPC.TL_messageMediaDocument && msg.media.document != null && !(msg.media.document instanceof TLRPC.TL_documentEmpty)) {
                var doc = msg.media.document;
                if (MessageObject.isStickerMessage(msg) || MessageObject.isAnimatedStickerMessage(msg) || "application/x-tgsticker".equals(doc.mime_type)) {
                    target.documentType = AyuConstants.DOCUMENT_TYPE_STICKER;
                    var buffer = new NativeByteBuffer(doc.getObjectSize());
                    doc.serializeToStream(buffer);
                    buffer.rewind();
                    byte[] bytes = new byte[buffer.limit()];
                    buffer.buffer.get(bytes);
                    target.documentSerialized = bytes;
                    buffer.reuse();
                } else {
                    target.documentType = AyuConstants.DOCUMENT_TYPE_FILE;
                    target.mimeType = doc.mime_type;

                    File attachFile = resolveAttachFile(accountId, msg);
                    if (attachFile != null && attachFile.exists()) {
                        if (needToSaveMedia) {
                            File dest = new File(AyuMessagesController.attachmentsPath, AyuUtils.getFilename(msg, attachFile));
                            if (AyuUtils.moveOrCopyFile(attachFile, dest)) {
                                target.mediaPath = dest.getAbsolutePath();
                            } else {
                                target.mediaPath = attachFile.getAbsolutePath();
                            }
                        } else {
                            target.mediaPath = attachFile.getAbsolutePath();
                        }
                    }

                    if (doc.attributes != null && !doc.attributes.isEmpty()) {
                        int attrSize = 4;
                        for (var attr : doc.attributes) {
                            attrSize += attr.getObjectSize();
                        }
                        var attrBuf = new NativeByteBuffer(attrSize);
                        attrBuf.writeInt32(doc.attributes.size());
                        for (var attr : doc.attributes) {
                            attr.serializeToStream(attrBuf);
                        }
                        attrBuf.rewind();
                        byte[] attrBytes = new byte[attrBuf.limit()];
                        attrBuf.buffer.get(attrBytes);
                        target.documentAttributesSerialized = attrBytes;
                        attrBuf.reuse();
                    }

                    if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                        int thumbSize = 4;
                        for (var thumb : doc.thumbs) {
                            thumbSize += thumb.getObjectSize();
                        }
                        var thumbBuf = new NativeByteBuffer(thumbSize);
                        thumbBuf.writeInt32(doc.thumbs.size());
                        for (var thumb : doc.thumbs) {
                            thumb.serializeToStream(thumbBuf);
                        }
                        thumbBuf.rewind();
                        byte[] thumbBytes = new byte[thumbBuf.limit()];
                        thumbBuf.buffer.get(thumbBytes);
                        target.thumbsSerialized = thumbBytes;
                        thumbBuf.reuse();

                        for (var thumb : doc.thumbs) {
                            if (thumb != null && thumb.location != null) {
                                File thumbFile = fileLoader.getPathToAttach(thumb, true);
                                if (thumbFile != null && thumbFile.exists()) {
                                    if (needToSaveMedia) {
                                        File destThumb = new File(AyuMessagesController.attachmentsPath, AyuUtils.getFilename(thumb, thumbFile));
                                        if (AyuUtils.moveOrCopyFile(thumbFile, destThumb)) {
                                            target.hqThumbPath = destThumb.getAbsolutePath();
                                            break;
                                        }
                                    } else {
                                        target.hqThumbPath = thumbFile.getAbsolutePath();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("AyuGram", "Failed in mapMedia(prefs, target)", e);
        }
    }

    private static File resolveAttachFile(int accountId, TLRPC.Message msg) {
        if (!TextUtils.isEmpty(msg.attachPath)) {
            File f = new File(msg.attachPath);
            if (f.exists()) {
                return f;
            }
        }
        var fileLoader = FileLoader.getInstance(accountId);
        File f = fileLoader.getPathToMessage(msg);
        if (f != null && f.exists()) {
            return f;
        }
        if (msg.media != null) {
            if (msg.media.document != null) {
                f = fileLoader.getPathToAttach(msg.media.document, true);
                if (f != null && f.exists()) {
                    return f;
                }
                f = fileLoader.getPathToAttach(msg.media.document, false);
                if (f != null && f.exists()) {
                    return f;
                }
            } else if (msg.media.photo != null && msg.media.photo.sizes != null && !msg.media.photo.sizes.isEmpty()) {
                var photoSize = FileLoader.getClosestPhotoSizeWithSize(msg.media.photo.sizes, AndroidUtilities.getPhotoSize());
                if (photoSize != null) {
                    f = fileLoader.getPathToAttach(photoSize, true);
                    if (f != null && f.exists()) {
                        return f;
                    }
                    f = fileLoader.getPathToAttach(photoSize, false);
                    if (f != null && f.exists()) {
                        return f;
                    }
                }
            }
        }
        return f;
    }

    public static void map(AyuMessageBase source, TLRPC.Message target, int accountId) {
        long clientUserId = UserConfig.getInstance(accountId).getClientUserId();

        target.dialog_id = source.dialogId;
        target.grouped_id = source.groupedId;
        target.id = source.messageId;
        target.date = source.date;
        target.flags = source.flags;
        target.out = (source.flags & 2) != 0 || (source.fromId != 0 && source.fromId == clientUserId);
        target.mentioned = (source.flags & 16) != 0;
        target.media_unread = (source.flags & 32) != 0;
        target.silent = (source.flags & 8192) != 0;
        target.post = (source.flags & 16384) != 0;
        target.from_scheduled = (source.flags & 262144) != 0;
        target.legacy = (source.flags & 524288) != 0;
        target.edit_hide = (source.flags & 2097152) != 0;
        target.pinned = (source.flags & 16777216) != 0;

        target.edit_date = source.editDate;
        target.views = source.views;
        target.message = source.text != null ? source.text : "";

        target.peer_id = MessagesController.getInstance(accountId).getPeer(source.peerId != 0 ? source.peerId : source.dialogId);
        if (source.fromId != 0) {
            target.from_id = MessagesController.getInstance(accountId).getPeer(source.fromId);
        }

        if (source.fwdFlags != 0 || source.fwdDate != 0) {
            target.fwd_from = new TLRPC.TL_messageFwdHeader();
            target.fwd_from.flags = source.fwdFlags;
            if (source.fwdFromId != 0) {
                target.fwd_from.from_id = MessagesController.getInstance(accountId).getPeer(source.fwdFromId);
            }
            target.fwd_from.from_name = source.fwdName;
            target.fwd_from.date = source.fwdDate;
            target.fwd_from.post_author = source.fwdPostAuthor;
        }

        if (source.replyFlags != 0 || source.replyMessageId != 0) {
            target.reply_to = new TLRPC.TL_messageReplyHeader();
            target.reply_to.flags = source.replyFlags;
            target.reply_to.reply_to_msg_id = source.replyMessageId;
            if (source.replyPeerId != 0) {
                target.reply_to.reply_to_peer_id = MessagesController.getInstance(accountId).getPeer(source.replyPeerId);
            }
            target.reply_to.reply_to_top_id = source.replyTopId;
            target.reply_to.forum_topic = source.replyForumTopic;
        }

        if (source.textEntities != null && source.textEntities.length > 0) {
            try {
                var buffer = new NativeByteBuffer(source.textEntities.length);
                buffer.writeBytes(source.textEntities);
                buffer.rewind();
                int count = buffer.readInt32(false);
                target.entities = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    var entity = TLRPC.MessageEntity.TLdeserialize(buffer, buffer.readInt32(false), false);
                    if (entity != null) {
                        target.entities.add(entity);
                    }
                }
                buffer.reuse();
            } catch (Exception e) {
                Log.e("AyuGram", "Failed to deserialize textEntities", e);
            }
        }
    }

    public static void mapMedia(AyuMessageBase source, TLRPC.Message target) {
        if (source.documentType == AyuConstants.DOCUMENT_TYPE_NONE) {
            return;
        }

        try {
            if (source.documentType == AyuConstants.DOCUMENT_TYPE_PHOTO && !TextUtils.isEmpty(source.mediaPath)) {
                File file = new File(source.mediaPath);
                if (!file.exists()) {
                    return;
                }
                target.media = new TLRPC.TL_messageMediaPhoto();
                target.media.flags |= 1;
                target.media.photo = new TLRPC.TL_photo();
                target.media.photo.has_stickers = false;
                target.media.photo.date = source.date;

                Pair<Integer, Integer> sizePair = AyuUtils.extractImageSizeFromName(file.getName());
                if (sizePair == null) {
                    sizePair = AyuUtils.extractImageSizeFromFile(source.mediaPath);
                }
                int w = sizePair != null ? sizePair.first : 500;
                int h = sizePair != null ? sizePair.second : 500;

                var photoSize = new TLRPC.TL_photoSize();
                photoSize.size = (int) file.length();
                photoSize.w = w;
                photoSize.h = h;
                photoSize.type = "y";
                photoSize.location = new AyuFileLocation(source.mediaPath);
                target.media.photo.sizes.add(photoSize);
                target.attachPath = source.mediaPath;
                target.flags |= 512;
            } else if (source.documentType == AyuConstants.DOCUMENT_TYPE_STICKER && source.documentSerialized != null && source.documentSerialized.length > 0) {
                var buffer = new NativeByteBuffer(source.documentSerialized.length);
                buffer.writeBytes(source.documentSerialized);
                buffer.rewind();
                target.media = new TLRPC.TL_messageMediaDocument();
                target.media.flags |= 1;
                target.media.document = TLRPC.Document.TLdeserialize(buffer, buffer.readInt32(false), false);
                buffer.reuse();
                target.flags |= 512;
            } else if (source.documentType == AyuConstants.DOCUMENT_TYPE_FILE && !TextUtils.isEmpty(source.mediaPath)) {
                File file = new File(source.mediaPath);
                if (!file.exists()) {
                    return;
                }
                target.media = new TLRPC.TL_messageMediaDocument();
                target.media.flags |= 1;

                var doc = new TLRPC.TL_document();
                doc.localPath = source.mediaPath;
                doc.file_name = AyuUtils.getReadableFilename(file.getName());
                doc.file_name_fixed = doc.file_name;
                doc.size = file.length();
                doc.mime_type = source.mimeType != null ? source.mimeType : "application/octet-stream";
                doc.date = source.date;

                if (source.documentAttributesSerialized != null && source.documentAttributesSerialized.length > 0) {
                    var attrBuf = new NativeByteBuffer(source.documentAttributesSerialized.length);
                    attrBuf.writeBytes(source.documentAttributesSerialized);
                    attrBuf.rewind();
                    int count = attrBuf.readInt32(false);
                    for (int i = 0; i < count; i++) {
                        var attr = TLRPC.DocumentAttribute.TLdeserialize(attrBuf, attrBuf.readInt32(false), false);
                        if (attr != null) {
                            doc.attributes.add(attr);
                        }
                    }
                    attrBuf.reuse();
                }

                if (source.thumbsSerialized != null && source.thumbsSerialized.length > 0) {
                    var thumbBuf = new NativeByteBuffer(source.thumbsSerialized.length);
                    thumbBuf.writeBytes(source.thumbsSerialized);
                    thumbBuf.rewind();
                    int count = thumbBuf.readInt32(false);
                    for (int i = 0; i < count; i++) {
                        var thumb = TLRPC.PhotoSize.TLdeserialize(0, 0, 0, thumbBuf, thumbBuf.readInt32(false), false);
                        if (thumb != null) {
                            doc.thumbs.add(thumb);
                        }
                    }
                    thumbBuf.reuse();
                }

                target.media.document = doc;
                target.attachPath = source.mediaPath;
                target.flags |= 512;
            }
        } catch (Exception e) {
            Log.e("AyuGram", "Failed in mapMedia(source, target)", e);
        }
    }
}
