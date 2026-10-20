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
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.SparseArray;
import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.messages.AyuMessagesController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AyuHistoryHook {

    public static Pair<Integer, Integer> getMinAndMaxIds(ArrayList<MessageObject> messages) {
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;

        if (messages != null) {
            for (var msg : messages) {
                if (msg == null || !msg.isSent()) {
                    continue;
                }
                int id = msg.getId();
                if (id == 0) {
                    continue;
                }
                if (id < minId) {
                    minId = id;
                }
                if (id > maxId) {
                    maxId = id;
                }
            }
        }

        if (minId == Integer.MAX_VALUE) {
            minId = 0;
        }
        if (maxId == Integer.MIN_VALUE) {
            maxId = 0;
        }

        return new Pair<>(minId, maxId);
    }

    public static void doHook(
            int currentAccount,
            ArrayList<MessageObject> messArr,
            SparseArray<MessageObject>[] messagesDict,
            int startId,
            int endId,
            long dialogId,
            int limit,
            int topicId,
            boolean isSecretChat
    ) {
        try {
            long userId = UserConfig.getInstance(currentAccount).getClientUserId();
            var ayuController = AyuMessagesController.getInstance();

            List<DeletedMessageFull> deletedMessages = ayuController.getMessages(userId, dialogId, topicId, startId, endId, limit);
            if (deletedMessages == null || deletedMessages.isEmpty()) {
                return;
            }

            var groupedIds = new ArrayList<Long>();
            for (var del : deletedMessages) {
                if (del.message.groupedId != 0 && !groupedIds.contains(del.message.groupedId)) {
                    groupedIds.add(del.message.groupedId);
                }
            }

            for (long gId : groupedIds) {
                var groupMsgs = ayuController.getMessagesGrouped(userId, dialogId, gId);
                for (var gm : groupMsgs) {
                    boolean exists = false;
                    for (var existing : deletedMessages) {
                        if (existing.message.messageId == gm.message.messageId) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        deletedMessages.add(gm);
                    }
                }
            }

            var usersDict = new LongSparseArray<TLRPC.User>();
            var chatsDict = new LongSparseArray<TLRPC.Chat>();

            for (var deletedFull : deletedMessages) {
                var deleted = deletedFull.message;
                if (messagesDict[0] != null && messagesDict[0].get(deleted.messageId) != null) {
                    continue;
                }

                var tlMsg = new TLRPC.TL_message();
                AyuMessageUtils.map(deleted, tlMsg, currentAccount);
                AyuMessageUtils.mapMedia(deleted, tlMsg);
                tlMsg.ayuDeleted = true;

                if (deletedFull.reactions != null && !deletedFull.reactions.isEmpty()) {
                    tlMsg.reactions = new TLRPC.TL_messageReactions();
                    tlMsg.flags |= 1048576;
                    int order = 0;
                    for (var r : deletedFull.reactions) {
                        var rc = new TLRPC.TL_reactionCount();
                        rc.count = r.count;
                        rc.chosen = r.selfSelected;
                        rc.chosen_order = order++;
                        if (r.isCustom) {
                            var custom = new TLRPC.TL_reactionCustomEmoji();
                            custom.document_id = r.documentId;
                            rc.reaction = custom;
                        } else {
                            var emoji = new TLRPC.TL_reactionEmoji();
                            emoji.emoticon = r.emoticon;
                            rc.reaction = emoji;
                        }
                        tlMsg.reactions.results.add(rc);
                    }
                }

                var msgObj = new MessageObject(currentAccount, tlMsg, usersDict, chatsDict, true, true);
                if (!TextUtils.isEmpty(deleted.hqThumbPath) && msgObj.photoThumbs != null && !msgObj.photoThumbs.isEmpty()) {
                    msgObj.photoThumbs.get(0).location = new com.radolyn.ayugram.utils.AyuFileLocation(deleted.hqThumbPath);
                }

                messArr.add(msgObj);
                if (messagesDict[0] != null) {
                    messagesDict[0].put(msgObj.getId(), msgObj);
                }
            }

            for (var msgObj : messArr) {
                if (msgObj.isReply() && msgObj.replyMessageObject == null && msgObj.messageOwner.reply_to != null) {
                    int replyId = msgObj.messageOwner.reply_to.reply_to_msg_id;
                    MessageObject replyTarget = messagesDict[0] != null ? messagesDict[0].get(replyId) : null;
                    if (replyTarget == null) {
                        var delReply = ayuController.getMessage(userId, dialogId, replyId);
                        if (delReply != null && delReply.message != null) {
                            var replyTl = new TLRPC.TL_message();
                            AyuMessageUtils.map(delReply.message, replyTl, currentAccount);
                            AyuMessageUtils.mapMedia(delReply.message, replyTl);
                            replyTl.ayuDeleted = true;
                            replyTarget = new MessageObject(currentAccount, replyTl, usersDict, chatsDict, false, false);
                        }
                    }
                    if (replyTarget != null) {
                        msgObj.replyMessageObject = replyTarget;
                    }
                }
            }

            Comparator<MessageObject> comparator = (o1, o2) -> {
                int id1 = o1.getId();
                int id2 = o2.getId();
                if (o1.messageOwner != null && o2.messageOwner != null && o1.messageOwner.date != o2.messageOwner.date) {
                    return Integer.compare(o2.messageOwner.date, o1.messageOwner.date);
                }
                if (isSecretChat) {
                    return Integer.compare(id1, id2);
                }
                return Integer.compare(id2, id1);
            };
            Collections.sort(messArr, comparator);
        } catch (Exception e) {
            Log.e("AyuGram", "Error in AyuHistoryHook.doHook", e);
        }
    }
}
