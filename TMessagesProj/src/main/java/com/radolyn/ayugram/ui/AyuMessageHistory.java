/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.proprietary.AyuMessageUtils;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class AyuMessageHistory extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final MessageObject messageObject;
    private List<EditedMessage> messages;
    private int rowCount;
    private RecyclerListView listView;

    public AyuMessageHistory(MessageObject messageObject) {
        this.messageObject = messageObject;

        updateHistory();
    }

    private void updateHistory() {
        var messagesController = AyuMessagesController.getInstance();
        messages = messagesController.getRevisions(getUserConfig().clientUserId, messageObject.messageOwner.dialog_id, messageObject.messageOwner.id);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        // Include all saved past revisions + 1 row at the bottom for the current (latest) version
        rowCount = messages.size() + 1;
    }

    private String buildEditCountSubtitle() {
        int editCount = messages != null ? messages.size() : 0;
        if (editCount == 0 && (messageObject.messageOwner.flags & TLRPC.MESSAGE_FLAG_EDITED) != 0) {
            return String.format(Locale.US, "MSG #%d • Edited (1+ times)", messageObject.messageOwner.id);
        }
        return String.format(
                Locale.US,
                LocaleController.getString("EditedTimesSubtitle", R.string.EditedTimesSubtitle),
                editCount,
                editCount + 1
        );
    }

    @Override
    public View createView(Context context) {
        long peerDialogId = !messages.isEmpty() ? messages.get(0).dialogId : messageObject.getDialogId();
        var peer = getMessagesController().getUserOrChat(peerDialogId);

        String name;
        if (peer == null) {
            name = LocaleController.getString("EditsHistoryTitle", R.string.EditsHistoryTitle);
        } else if (peer instanceof TLRPC.User) {
            name = ContactsController.formatName(((TLRPC.User) peer).first_name, ((TLRPC.User) peer).last_name);
        } else if (peer instanceof TLRPC.Chat) {
            name = ((TLRPC.Chat) peer).title;
        } else {
            name = LocaleController.getString("EditsHistoryTitle", R.string.EditsHistoryTitle);
        }

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(name);
        actionBar.setSubtitle(buildEditCountSubtitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        LinearLayoutManager layoutManager;
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ListAdapter adapter;
        listView.setAdapter(adapter = new AyuMessageHistory.ListAdapter(context));

        return fragmentView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, AyuConstants.MESSAGE_EDITED_NOTIFICATION);

        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == AyuConstants.MESSAGE_EDITED_NOTIFICATION) {
            var dialogId = (long) args[0];
            var messageId = (int) args[1];

            if (dialogId == messageObject.messageOwner.dialog_id && messageId == messageObject.messageOwner.id) {
                updateHistory();
                if (actionBar != null) {
                    actionBar.setSubtitle(buildEditCountSubtitle());
                }
                if (listView != null && listView.getAdapter() != null) {
                    listView.getAdapter().notifyDataSetChanged();
                }
            }
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();

        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, AyuConstants.MESSAGE_EDITED_NOTIFICATION);
    }

    private String formatVersionTimestamp(int timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        return LocaleController.formatString(
                "formatDateAtTime",
                R.string.formatDateAtTime,
                LocaleController.getInstance().formatterYear.format(new Date(timestamp * 1000L)),
                LocaleController.getInstance().formatterDayWithSeconds.format(new Date(timestamp * 1000L))
        );
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);

                HeaderCell versionHeader = new HeaderCell(context, Theme.key_windowBackgroundWhiteBlueHeader, 16, 8, false);
                versionHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                container.addView(versionHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                AyuMessageCell messageCell = new AyuMessageCell(context, getParentActivity(), AyuMessageHistory.this);
                container.addView(messageCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                view = container;
            } else {
                view = null;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 1) {
                LinearLayout container = (LinearLayout) holder.itemView;
                HeaderCell versionHeader = (HeaderCell) container.getChildAt(0);
                AyuMessageCell ayuMessageDetailCell = (AyuMessageCell) container.getChildAt(1);

                if (position < messages.size()) {
                    var editedMessage = messages.get(position);
                    var msg = createMessageObject(editedMessage);

                    String versionLabel;
                    if (position == 0) {
                        versionLabel = String.format(Locale.US, "%s (#1) • %s",
                                LocaleController.getString("VersionHistoryOriginal", R.string.VersionHistoryOriginal),
                                formatVersionTimestamp(editedMessage.date > 0 ? editedMessage.date : editedMessage.entityCreateDate));
                    } else {
                        versionLabel = String.format(Locale.US, "Version #%d • %s",
                                position + 1,
                                formatVersionTimestamp(editedMessage.entityCreateDate));
                    }
                    if (!TextUtils.isEmpty(editedMessage.mediaPath)) {
                        versionLabel += " • " + LocaleController.getString("VersionHistoryMediaReplaced", R.string.VersionHistoryMediaReplaced);
                    }
                    versionHeader.setText(versionLabel);

                    ayuMessageDetailCell.setMessageObject(msg, null, false, false);
                    ayuMessageDetailCell.setEditedMessage(editedMessage);
                    ayuMessageDetailCell.setId(position);
                } else {
                    // Current (latest) version row
                    var currentEditedWrapper = new EditedMessage();
                    currentEditedWrapper.text = messageObject.messageOwner.message;
                    File currentPath = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
                    if (currentPath != null && currentPath.exists()) {
                        currentEditedWrapper.mediaPath = currentPath.getAbsolutePath();
                    }

                    int editTs = messageObject.messageOwner.edit_date > 0 ? messageObject.messageOwner.edit_date : messageObject.messageOwner.date;
                    String currentLabel = String.format(Locale.US, "%s (#%d) • %s",
                            LocaleController.getString("VersionHistoryCurrent", R.string.VersionHistoryCurrent),
                            messages.size() + 1,
                            formatVersionTimestamp(editTs));
                    versionHeader.setText(currentLabel);

                    var msgCopy = new TLRPC.TL_message();
                    var tempPrefs = new com.radolyn.ayugram.messages.AyuSavePreferences(messageObject.messageOwner, currentAccount);
                    AyuMessageUtils.map(tempPrefs, currentEditedWrapper);
                    AyuMessageUtils.map(currentEditedWrapper, msgCopy, currentAccount);
                    msgCopy.media = messageObject.messageOwner.media;
                    msgCopy.attachPath = messageObject.messageOwner.attachPath;
                    msgCopy.date = editTs;
                    msgCopy.edit_hide = true;
                    if (messageObject.messageOwner.replyMessage != null) {
                        msgCopy.replyMessage = messageObject.messageOwner.replyMessage;
                        msgCopy.reply_to = messageObject.messageOwner.reply_to;
                    }

                    var currentMsgObj = new MessageObject(getCurrentAccount(), msgCopy, false, true);
                    ayuMessageDetailCell.setMessageObject(currentMsgObj, null, false, false);
                    ayuMessageDetailCell.setEditedMessage(currentEditedWrapper);
                    ayuMessageDetailCell.setId(position);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position >= 0 && position < rowCount ? 1 : 0;
        }

        private MessageObject createMessageObject(EditedMessage editedMessage) {
            var msg = new TLRPC.TL_message();
            AyuMessageUtils.map(editedMessage, msg, currentAccount);
            AyuMessageUtils.mapMedia(editedMessage, msg);

            msg.date = editedMessage.entityCreateDate;
            msg.edit_hide = true;

            if (editedMessage.documentType == AyuConstants.DOCUMENT_TYPE_FILE && messageObject.messageOwner.media != null && Objects.equals(editedMessage.mediaPath, FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).getAbsolutePath())) {
                msg.media.document = messageObject.messageOwner.media.document;
            }

            if (messageObject.messageOwner.replyMessage != null) {
                msg.replyMessage = messageObject.messageOwner.replyMessage;
                msg.reply_to = messageObject.messageOwner.reply_to;
            }

            return new MessageObject(getCurrentAccount(), msg, false, true);
        }
    }
}
