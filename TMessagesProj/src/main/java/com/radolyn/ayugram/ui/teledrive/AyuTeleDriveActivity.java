/*
 * Native AyuGram UI for TeleDrive Virtual Folder & File Manager
 * Supports both standalone view and seamless In-Chat Google-Drive-style folder mode inside ChatActivity.
 * Uses only native Telegram R.drawable.* vector icons (zero emojis).
 */

package com.radolyn.ayugram.ui.teledrive;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.radolyn.ayugram.database.entities.TeleDriveFile;
import com.radolyn.ayugram.database.entities.TeleDriveFolder;
import com.radolyn.ayugram.database.entities.TeleDriveWorkspace;
import com.radolyn.ayugram.teledrive.AyuTeleDriveController;
import com.radolyn.ayugram.teledrive.TeleDriveModels;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class AyuTeleDriveActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SWITCH_CHANNEL = 1;
    private static final int MENU_NEW_FOLDER = 2;
    private static final int MENU_UPLOAD_FILE = 3;
    private static final int MENU_SORT = 4;
    private static final int MENU_VAULT_SETTINGS = 5;

    public static final int SORT_DATE_DESC = 0;
    public static final int SORT_NAME_ASC = 1;
    public static final int SORT_SIZE_DESC = 2;
    public static final int SORT_DATE_ASC = 3;

    private long activeDialogId;
    private InChatDriveView driveView;

    public AyuTeleDriveActivity() {
        this(0L);
    }

    public AyuTeleDriveActivity(long dialogId) {
        this.activeDialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (activeDialogId == 0L) {
            List<TeleDriveWorkspace> known = AyuTeleDriveController.getInstance().getDao().getAllWorkspaces();
            if (!known.isEmpty()) {
                activeDialogId = known.get(0).chatId;
            } else {
                activeDialogId = getUserConfig().getClientUserId();
            }
        }
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (driveView == null) return;
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            driveView.refreshListOnly();
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long did = (Long) args[0];
            if (did == activeDialogId) {
                driveView.reloadData(true);
            }
        }
    }

    @Override
    public boolean onBackPressed() {
        if (driveView != null && driveView.canNavigateUp()) {
            driveView.navigateUpFolder();
            return false;
        }
        return super.onBackPressed();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("TeleDrive");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (driveView != null && driveView.canNavigateUp()) {
                        driveView.navigateUpFolder();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_SWITCH_CHANNEL && driveView != null) {
                    driveView.showChannelSwitcherDialog();
                } else if (id == MENU_NEW_FOLDER && driveView != null) {
                    driveView.showCreateFolderDialog();
                } else if (id == MENU_UPLOAD_FILE && driveView != null) {
                    driveView.showUploadFileDialog();
                } else if (id == MENU_SORT && driveView != null) {
                    driveView.showSortDialog();
                } else if (id == MENU_VAULT_SETTINGS && driveView != null) {
                    driveView.showVaultSettingsSheet();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem moreItem = menu.addItem(0, R.drawable.ic_ab_other);
        moreItem.addSubItem(MENU_SWITCH_CHANNEL, R.drawable.msg_channel, "Switch Channel / Vault");
        moreItem.addSubItem(MENU_NEW_FOLDER, R.drawable.msg_addfolder, "New Folder");
        moreItem.addSubItem(MENU_UPLOAD_FILE, R.drawable.msg_sendfile, "Upload File to Folder");
        moreItem.addSubItem(MENU_SORT, R.drawable.msg_reorder, "Sort Folders & Files");
        moreItem.addSubItem(MENU_VAULT_SETTINGS, R.drawable.msg_settings_old, "Vault & Sync Settings");

        driveView = new InChatDriveView(context, this, currentAccount, activeDialogId, null);
        driveView.setOnHeaderUpdateListener((title, subtitle) -> {
            actionBar.setTitle(title);
            actionBar.setSubtitle(subtitle);
        });
        driveView.reloadData(true);
        fragmentView = driveView;
        return fragmentView;
    }

    // =========================================================================
    // Static Entrypoints for ChatActivity 3-Dot Menu & Context Menu
    // =========================================================================

    public static void toggleInChatDriveMode(
            BaseFragment fragment,
            int accountId,
            long dialogId,
            InChatDriveView inChatDriveView,
            ActionBarMenuSubItem toggleMenuItem
    ) {
        if (fragment == null || fragment.getParentActivity() == null || inChatDriveView == null) {
            return;
        }
        var ctrl = AyuTeleDriveController.getInstance();
        boolean currentlyOn = ctrl.isDriveModeEnabled(dialogId);
        if (currentlyOn) {
            ctrl.setDriveModeEnabled(dialogId, false);
            inChatDriveView.setVisibility(View.GONE);
            if (toggleMenuItem != null) {
                toggleMenuItem.setText("TeleDrive Mode: OFF");
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "TeleDrive Mode disabled for this chat").show();
            return;
        }

        long linkedVault = ctrl.getLinkedVaultChatId(dialogId);
        if (linkedVault != 0L) {
            ctrl.setDriveModeEnabled(dialogId, true);
            inChatDriveView.setVisibility(View.VISIBLE);
            inChatDriveView.reloadData(true);
            if (toggleMenuItem != null) {
                toggleMenuItem.setText("TeleDrive Mode: ON");
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "TeleDrive Mode enabled").show();
        } else {
            showSetupVaultForChannelDialog(fragment, accountId, dialogId, () -> {
                ctrl.setDriveModeEnabled(dialogId, true);
                inChatDriveView.setVisibility(View.VISIBLE);
                inChatDriveView.reloadData(true);
                if (toggleMenuItem != null) {
                    toggleMenuItem.setText("TeleDrive Mode: ON");
                }
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "TeleDrive Mode enabled! Folders at top, uncategorized items below.").show();
            });
        }
    }

    public static void showInChatVaultSettings(
            BaseFragment fragment,
            int accountId,
            long dialogId,
            InChatDriveView inChatDriveView,
            ActionBarMenuSubItem toggleMenuItem
    ) {
        if (inChatDriveView != null) {
            inChatDriveView.showVaultSettingsSheet();
        }
    }

    public static void showSetupVaultForChannelDialog(
            BaseFragment fragment,
            int accountId,
            long dialogId,
            Runnable onConfigured
    ) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        var ctrl = AyuTeleDriveController.getInstance();
        String chatTitle = ctrl.getDialogTitle(accountId, dialogId);
        boolean canPostHere = ctrl.canPostToDialog(accountId, dialogId);

        List<CharSequence> items = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();

        items.add("Create New Vault Channel for Index");
        actions.add(1);
        icons.add(R.drawable.msg_addfolder);

        items.add("Use Existing Vault / Saved Messages");
        actions.add(2);
        icons.add(R.drawable.msg_folders);

        if (canPostHere) {
            items.add("Store index.json Directly in This Chat");
            actions.add(3);
            icons.add(R.drawable.msg_channel);
        }

        items.add("Keep Index Local on This Device Only");
        actions.add(4);
        icons.add(R.drawable.msg_saved);

        int[] iconArray = new int[icons.size()];
        for (int i = 0; i < icons.size(); i++) iconArray[i] = icons.get(i);

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Enable TeleDrive Mode");
        builder.setItems(items.toArray(new CharSequence[0]), iconArray, (d, which) -> {
            int act = actions.get(which);
            if (act == 1) {
                AlertDialog.Builder nameDialog = new AlertDialog.Builder(fragment.getParentActivity());
                nameDialog.setTitle("New TeleDrive Vault Name");
                EditTextBoldCursor input = new EditTextBoldCursor(fragment.getParentActivity());
                input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
                input.setHint("Vault channel name");
                String suggested = "Vault - " + chatTitle;
                if (suggested.length() > 48) suggested = suggested.substring(0, 48);
                input.setText(suggested);
                input.setSingleLine(true);
                input.setSelection(input.length());
                FrameLayout fl = new FrameLayout(fragment.getParentActivity());
                fl.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
                fl.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42));
                nameDialog.setView(fl);
                nameDialog.setPositiveButton("Create Vault & Start", (d2, w2) -> {
                    String vaultName = input.getText().toString().trim();
                    ctrl.createVaultChannel(accountId, vaultName, newVaultDialogId -> {
                        if (newVaultDialogId != 0L) {
                            ctrl.setLinkedVaultChatId(accountId, dialogId, newVaultDialogId);
                            if (onConfigured != null) onConfigured.run();
                        } else {
                            BulletinFactory.of(fragment).createErrorBulletin("Failed to create Vault channel").show();
                        }
                    });
                });
                nameDialog.setNegativeButton("Cancel", null);
                fragment.showDialog(nameDialog.create());
            } else if (act == 2) {
                List<TeleDriveModels.ChatSummary> postable = new ArrayList<>();
                List<CharSequence> names = new ArrayList<>();
                List<Integer> rowIcons = new ArrayList<>();
                for (TeleDriveModels.ChatSummary c : ctrl.listAvailableChats(accountId)) {
                    if (c.canUpload) {
                        postable.add(c);
                        names.add(c.title);
                        rowIcons.add(TeleDriveModels.CHAT_KIND_SAVED.equals(c.kind) ? R.drawable.msg_saved : R.drawable.msg_folders);
                    }
                }
                int[] pickIcons = new int[rowIcons.size()];
                for (int i = 0; i < rowIcons.size(); i++) pickIcons[i] = rowIcons.get(i);
                AlertDialog.Builder pick = new AlertDialog.Builder(fragment.getParentActivity());
                pick.setTitle("Select Vault for index.json");
                pick.setItems(names.toArray(new CharSequence[0]), pickIcons, (d3, w3) -> {
                    TeleDriveModels.ChatSummary chosen = postable.get(w3);
                    ctrl.setLinkedVaultChatId(accountId, dialogId, chosen.dialogId);
                    if (onConfigured != null) onConfigured.run();
                });
                fragment.showDialog(pick.create());
            } else if (act == 3) {
                ctrl.setLinkedVaultChatId(accountId, dialogId, dialogId);
                if (onConfigured != null) onConfigured.run();
            } else if (act == 4) {
                ctrl.setLocalOnlyMode(dialogId);
                if (onConfigured != null) onConfigured.run();
            }
        });
        fragment.showDialog(builder.create());
    }

    /** Quick static helper so ChatActivity can assign any message to a TeleDrive folder with 1 click! */
    public static void showAssignMessageToFolderDialog(BaseFragment fragment, int accountId, long dialogId, TLRPC.Message message) {
        if (fragment == null || fragment.getParentActivity() == null || message == null) return;
        var ctrl = AyuTeleDriveController.getInstance();
        ctrl.getOrCreateWorkspace(accountId, dialogId);
        List<TeleDriveFolder> folders = ctrl.getDao().getAllFolders(dialogId);

        List<String> ids = new ArrayList<>();
        List<CharSequence> items = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();

        ids.add("__NEW__");
        items.add("Create New Folder & Save Here");
        icons.add(R.drawable.msg_addfolder);

        ids.add(TeleDriveModels.ROOT_ID);
        items.add(TeleDriveModels.ROOT_LABEL + " (Uncategorized)");
        icons.add(R.drawable.msg_folders);

        for (TeleDriveFolder f : folders) {
            ids.add(f.id);
            items.add(f.name);
            icons.add(R.drawable.msg_folders);
        }

        int[] iconArr = new int[icons.size()];
        for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Save to TeleDrive Folder");
        builder.setItems(items.toArray(new CharSequence[0]), iconArr, (d, which) -> {
            String chosen = ids.get(which);
            if ("__NEW__".equals(chosen)) {
                AlertDialog.Builder createBuilder = new AlertDialog.Builder(fragment.getParentActivity());
                createBuilder.setTitle("New TeleDrive Folder");
                EditTextBoldCursor input = new EditTextBoldCursor(fragment.getParentActivity());
                input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
                input.setHint("Folder name");
                input.setSingleLine(true);
                FrameLayout fl = new FrameLayout(fragment.getParentActivity());
                fl.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
                fl.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42));
                createBuilder.setView(fl);
                createBuilder.setPositiveButton("Create & Save", (d2, w2) -> {
                    String name = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(name)) {
                        TeleDriveFolder nf = ctrl.createFolder(accountId, dialogId, TeleDriveModels.ROOT_ID, name);
                        ctrl.addOrMoveMessageToFolder(accountId, dialogId, message, nf.id);
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Saved to folder: " + nf.name).show();
                    }
                });
                createBuilder.setNegativeButton("Cancel", null);
                fragment.showDialog(createBuilder.create());
            } else {
                ctrl.addOrMoveMessageToFolder(accountId, dialogId, message, chosen);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Saved to TeleDrive folder").show();
            }
        });
        fragment.showDialog(builder.create());
    }

    // =========================================================================
    // Embedded In-Chat Google-Drive-Style View (Top: Sorted Folders, Bottom: Uncategorized / Files)
    // =========================================================================

    public interface HeaderUpdateListener {
        void onHeaderUpdated(String title, String subtitle);
    }

    public static class InChatDriveView extends FrameLayout {
        private final BaseFragment hostFragment;
        private final int currentAccount;
        private long activeDialogId;
        private final Consumer<Integer> onJumpToChatMessage;
        private HeaderUpdateListener headerUpdateListener;

        private String currentFolderId = TeleDriveModels.ROOT_ID;
        private int sortMode = SORT_NAME_ASC;

        private TeleDriveWorkspace workspace;
        private List<TeleDriveFolder> allFolders = new ArrayList<>();
        private List<TeleDriveFolder> subFolders = new ArrayList<>();
        private List<TeleDriveFile> files = new ArrayList<>();
        private Map<String, Integer> folderItemCounts = new HashMap<>();

        private final RecyclerListView listView;
        private final DriveAdapter listAdapter;

        private int breadcrumbRow;
        private int newFolderActionRow;
        private int sortActionRow;
        private int syncActionRow;
        private int actionsDividerRow;
        private int foldersHeaderRow;
        private int foldersStartRow;
        private int foldersEndRow;
        private int foldersDividerRow;
        private int filesHeaderRow;
        private int filesStartRow;
        private int filesEndRow;
        private int emptyInfoRow;
        private int rowCount;

        public InChatDriveView(
                Context context,
                BaseFragment hostFragment,
                int currentAccount,
                long dialogId,
                Consumer<Integer> onJumpToChatMessage
        ) {
            super(context);
            this.hostFragment = hostFragment;
            this.currentAccount = currentAccount;
            this.activeDialogId = dialogId;
            this.onJumpToChatMessage = onJumpToChatMessage;

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            setClickable(true);

            listView = new RecyclerListView(context);
            listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            listView.setVerticalScrollBarEnabled(true);
            addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            listView.setAdapter(listAdapter = new DriveAdapter(context));

            listView.setOnItemClickListener((view, position) -> {
                if (position == breadcrumbRow) {
                    if (canNavigateUp()) {
                        navigateUpFolder();
                    } else {
                        showVaultSettingsSheet();
                    }
                } else if (position == newFolderActionRow) {
                    showCreateFolderDialog();
                } else if (position == sortActionRow) {
                    showSortDialog();
                } else if (position == syncActionRow) {
                    AyuTeleDriveController.getInstance().syncWorkspace(currentAccount, activeDialogId, false, () -> reloadData(false));
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Syncing channel messages & Vault index...").show();
                } else if (foldersStartRow != -1 && position >= foldersStartRow && position < foldersEndRow) {
                    TeleDriveFolder folder = subFolders.get(position - foldersStartRow);
                    currentFolderId = folder.id;
                    reloadData(false);
                } else if (filesStartRow != -1 && position >= filesStartRow && position < filesEndRow) {
                    TeleDriveFile file = files.get(position - filesStartRow);
                    AyuTeleDriveController.getInstance().openOrDownloadFile(hostFragment, currentAccount, file, this::refreshListOnly);
                }
            });

            listView.setOnItemLongClickListener((view, position) -> {
                if (foldersStartRow != -1 && position >= foldersStartRow && position < foldersEndRow) {
                    TeleDriveFolder folder = subFolders.get(position - foldersStartRow);
                    showFolderOptionsDialog(folder);
                    return true;
                } else if (filesStartRow != -1 && position >= filesStartRow && position < filesEndRow) {
                    TeleDriveFile file = files.get(position - filesStartRow);
                    showFileOptionsDialog(file);
                    return true;
                }
                return false;
            });
        }

        public void setOnHeaderUpdateListener(HeaderUpdateListener listener) {
            this.headerUpdateListener = listener;
        }

        public boolean canNavigateUp() {
            return !TeleDriveModels.ROOT_ID.equals(currentFolderId);
        }

        public void navigateUpFolder() {
            if (TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                return;
            }
            TeleDriveFolder current = AyuTeleDriveController.getInstance().getDao().getFolder(currentFolderId);
            currentFolderId = (current != null && !TextUtils.isEmpty(current.parentId)) ? current.parentId : TeleDriveModels.ROOT_ID;
            reloadData(false);
        }

        public void refreshListOnly() {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }

        public void reloadData(boolean triggerSync) {
            var controller = AyuTeleDriveController.getInstance();
            workspace = controller.getOrCreateWorkspace(currentAccount, activeDialogId);
            allFolders = controller.getDao().getAllFolders(activeDialogId);
            subFolders = controller.getDao().getSubFolders(activeDialogId, currentFolderId);
            files = controller.getDao().getFilesInFolder(activeDialogId, currentFolderId);

            // Compute item counts per folder
            folderItemCounts.clear();
            for (TeleDriveFile f : controller.getDao().getAllFiles(activeDialogId)) {
                if (!f.isHidden && f.folderId != null) {
                    folderItemCounts.put(f.folderId, folderItemCounts.getOrDefault(f.folderId, 0) + 1);
                }
            }

            sortFoldersAndFiles();
            updateRows();
            if (headerUpdateListener != null) {
                String wsTitle = workspace != null && !TextUtils.isEmpty(workspace.title) ? workspace.title : "TeleDrive";
                int totalItems = subFolders.size() + files.size();
                headerUpdateListener.onHeaderUpdated(wsTitle, buildBreadcrumbPath() + " • " + totalItems + " items");
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            if (triggerSync) {
                controller.syncWorkspace(currentAccount, activeDialogId, false, () -> reloadData(false));
            }
        }

        private void sortFoldersAndFiles() {
            if (subFolders != null) {
                if (sortMode == SORT_DATE_DESC) {
                    subFolders.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                } else if (sortMode == SORT_DATE_ASC) {
                    subFolders.sort((a, b) -> Long.compare(a.updatedAt, b.updatedAt));
                } else {
                    subFolders.sort((a, b) -> (a.name != null ? a.name : "").compareToIgnoreCase(b.name != null ? b.name : ""));
                }
            }
            if (files != null) {
                if (sortMode == SORT_NAME_ASC) {
                    files.sort((a, b) -> (a.name != null ? a.name : "").compareToIgnoreCase(b.name != null ? b.name : ""));
                } else if (sortMode == SORT_SIZE_DESC) {
                    files.sort((a, b) -> Long.compare(b.size, a.size));
                } else if (sortMode == SORT_DATE_ASC) {
                    files.sort((a, b) -> Integer.compare(a.date, b.date));
                } else {
                    files.sort((a, b) -> Integer.compare(b.date, a.date));
                }
            }
        }

        private void updateRows() {
            rowCount = 0;
            breadcrumbRow = rowCount++;
            newFolderActionRow = rowCount++;
            sortActionRow = rowCount++;
            syncActionRow = rowCount++;
            actionsDividerRow = rowCount++;

            // 1. TOP SECTION: SORTED FOLDERS
            foldersHeaderRow = rowCount++;
            if (!subFolders.isEmpty()) {
                foldersStartRow = rowCount;
                rowCount += subFolders.size();
                foldersEndRow = rowCount;
            } else {
                foldersStartRow = -1;
                foldersEndRow = -1;
            }
            foldersDividerRow = rowCount++;

            // 2. BOTTOM SECTION: UNCATEGORIZED MESSAGES & FILES (at Root) or FOLDER ITEMS
            filesHeaderRow = rowCount++;
            if (!files.isEmpty()) {
                filesStartRow = rowCount;
                rowCount += files.size();
                filesEndRow = rowCount;
                emptyInfoRow = -1;
            } else {
                filesStartRow = -1;
                filesEndRow = -1;
                emptyInfoRow = rowCount++;
            }
        }

        private String buildBreadcrumbPath() {
            if (TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                return TeleDriveModels.ROOT_LABEL;
            }
            Map<String, TeleDriveFolder> map = new HashMap<>();
            for (TeleDriveFolder f : allFolders) {
                map.put(f.id, f);
            }
            LinkedList<String> names = new LinkedList<>();
            String curr = currentFolderId;
            int guard = 0;
            while (!TeleDriveModels.ROOT_ID.equals(curr) && guard++ < 32) {
                TeleDriveFolder f = map.get(curr);
                if (f == null) break;
                names.addFirst(f.name);
                curr = f.parentId;
            }
            names.addFirst(TeleDriveModels.ROOT_LABEL);
            return TextUtils.join(" / ", names);
        }

        private String getSortLabel() {
            if (sortMode == SORT_NAME_ASC) return "Name (A-Z)";
            if (sortMode == SORT_SIZE_DESC) return "Largest Size";
            if (sortMode == SORT_DATE_ASC) return "Oldest First";
            return "Newest First";
        }

        // =====================================================================
        // Dialogs (All using native R.drawable.* icons, zero emojis)
        // =====================================================================

        public void showCreateFolderDialog() {
            if (hostFragment.getParentActivity() == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Create New Folder");
            EditTextBoldCursor input = createDialogInput("Folder name", "");
            builder.setView(wrapDialogInput(input));
            builder.setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    AyuTeleDriveController.getInstance().createFolder(currentAccount, activeDialogId, currentFolderId, name);
                    reloadData(false);
                }
            });
            builder.setNegativeButton("Cancel", null);
            hostFragment.showDialog(builder.create());
        }

        public void showFolderOptionsDialog(TeleDriveFolder folder) {
            if (hostFragment.getParentActivity() == null) return;
            CharSequence[] items = new CharSequence[]{
                    "Open Folder",
                    "Create Subfolder Inside",
                    "Rename Folder",
                    "Forward All Items in Folder",
                    "Delete Folder (Move items up)"
            };
            int[] icons = new int[]{
                    R.drawable.msg_folders,
                    R.drawable.msg_addfolder,
                    R.drawable.msg_edit,
                    R.drawable.msg_forward,
                    R.drawable.msg_delete
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle(folder.name);
            builder.setItems(items, icons, (d, which) -> {
                if (which == 0) {
                    currentFolderId = folder.id;
                    reloadData(false);
                } else if (which == 1) {
                    currentFolderId = folder.id;
                    reloadData(false);
                    showCreateFolderDialog();
                } else if (which == 2) {
                    showRenameFolderDialog(folder);
                } else if (which == 3) {
                    List<TeleDriveFile> folderFiles = AyuTeleDriveController.getInstance().getDao().getFilesInFolder(activeDialogId, folder.id);
                    if (folderFiles.isEmpty()) {
                        BulletinFactory.of(hostFragment).createErrorBulletin("Folder has no files to forward").show();
                    } else {
                        showForwardItemsDialog(folderFiles);
                    }
                } else if (which == 4) {
                    AyuTeleDriveController.getInstance().deleteFolder(currentAccount, activeDialogId, folder.id);
                    reloadData(false);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Folder deleted; items moved up").show();
                }
            });
            hostFragment.showDialog(builder.create());
        }

        private void showRenameFolderDialog(TeleDriveFolder folder) {
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Rename Folder");
            EditTextBoldCursor input = createDialogInput("Folder name", folder.name);
            builder.setView(wrapDialogInput(input));
            builder.setPositiveButton("Save", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    AyuTeleDriveController.getInstance().renameFolder(currentAccount, activeDialogId, folder.id, name);
                    reloadData(false);
                }
            });
            builder.setNegativeButton("Cancel", null);
            hostFragment.showDialog(builder.create());
        }

        private void showFileOptionsDialog(TeleDriveFile file) {
            if (hostFragment.getParentActivity() == null) return;
            List<CharSequence> items = new ArrayList<>();
            List<Integer> actions = new ArrayList<>();
            List<Integer> icons = new ArrayList<>();

            items.add("Open / Play / View");
            actions.add(0);
            icons.add(R.drawable.msg_view_file);

            items.add("Move to Folder");
            actions.add(1);
            icons.add(R.drawable.msg_folders);

            items.add("Rename in TeleDrive Index (index.json)");
            actions.add(2);
            icons.add(R.drawable.msg_edit);

            items.add("Edit Message / Caption");
            actions.add(3);
            icons.add(R.drawable.msg_edit);

            items.add("Forward to Another Chat");
            actions.add(4);
            icons.add(R.drawable.msg_forward);

            if (!TeleDriveModels.FILE_KIND_MESSAGE.equals(file.kind)) {
                items.add("Save to Downloads/TeleDrive");
                actions.add(5);
                icons.add(R.drawable.msg_download);
            }

            if (onJumpToChatMessage != null) {
                items.add("Show in Normal Chat View");
                actions.add(6);
                icons.add(R.drawable.msg_message);
            }

            items.add("Remove from TeleDrive Index");
            actions.add(7);
            icons.add(R.drawable.msg_delete);

            int[] iconArr = new int[icons.size()];
            for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);

            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle(file.name);
            builder.setItems(items.toArray(new CharSequence[0]), iconArr, (d, which) -> {
                int act = actions.get(which);
                if (act == 0) {
                    AyuTeleDriveController.getInstance().openOrDownloadFile(hostFragment, currentAccount, file, this::refreshListOnly);
                } else if (act == 1) {
                    showMoveFileDialog(file);
                } else if (act == 2) {
                    showRenameFileDialog(file);
                } else if (act == 3) {
                    showEditMessageOrIndexDialog(file);
                } else if (act == 4) {
                    showForwardItemsDialog(Collections.singletonList(file));
                } else if (act == 5) {
                    AyuTeleDriveController.getInstance().exportFileToDownloads(hostFragment, currentAccount, file);
                } else if (act == 6 && onJumpToChatMessage != null) {
                    onJumpToChatMessage.accept(file.messageId);
                } else if (act == 7) {
                    AyuTeleDriveController.getInstance().deleteFiles(currentAccount, activeDialogId, Collections.singletonList(file.messageId), false);
                    reloadData(false);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Removed from TeleDrive index").show();
                }
            });
            hostFragment.showDialog(builder.create());
        }

        private void showMoveFileDialog(TeleDriveFile file) {
            var ctrl = AyuTeleDriveController.getInstance();
            List<TeleDriveFolder> folders = ctrl.getDao().getAllFolders(activeDialogId);
            List<String> targetIds = new ArrayList<>();
            List<CharSequence> labels = new ArrayList<>();
            List<Integer> icons = new ArrayList<>();

            targetIds.add("__NEW__");
            labels.add("Create New Folder & Move Here");
            icons.add(R.drawable.msg_addfolder);

            targetIds.add(TeleDriveModels.ROOT_ID);
            labels.add(TeleDriveModels.ROOT_LABEL + " (Uncategorized)" + (TeleDriveModels.ROOT_ID.equals(file.folderId) ? " [Current]" : ""));
            icons.add(R.drawable.msg_folders);

            for (TeleDriveFolder f : folders) {
                targetIds.add(f.id);
                labels.add(f.name + (f.id.equals(file.folderId) ? " [Current]" : ""));
                icons.add(R.drawable.msg_folders);
            }

            int[] iconArr = new int[icons.size()];
            for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);

            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Move \"" + file.name + "\" to:");
            builder.setItems(labels.toArray(new CharSequence[0]), iconArr, (d, which) -> {
                String targetFolder = targetIds.get(which);
                if ("__NEW__".equals(targetFolder)) {
                    AlertDialog.Builder cb = new AlertDialog.Builder(hostFragment.getParentActivity());
                    cb.setTitle("New Folder Name");
                    EditTextBoldCursor input = createDialogInput("Folder name", "");
                    cb.setView(wrapDialogInput(input));
                    cb.setPositiveButton("Create & Move", (d2, w2) -> {
                        String name = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(name)) {
                            TeleDriveFolder nf = ctrl.createFolder(currentAccount, activeDialogId, currentFolderId, name);
                            ctrl.moveFiles(currentAccount, activeDialogId, Collections.singletonList(file.messageId), nf.id);
                            reloadData(false);
                            BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Moved to " + nf.name).show();
                        }
                    });
                    cb.setNegativeButton("Cancel", null);
                    hostFragment.showDialog(cb.create());
                } else {
                    ctrl.moveFiles(currentAccount, activeDialogId, Collections.singletonList(file.messageId), targetFolder);
                    reloadData(false);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Moved to folder").show();
                }
            });
            hostFragment.showDialog(builder.create());
        }

        private void showRenameFileDialog(TeleDriveFile file) {
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Rename in TeleDrive Index");
            EditTextBoldCursor input = createDialogInput("Display name in index.json", file.name);
            builder.setView(wrapDialogInput(input));
            builder.setPositiveButton("Save", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    AyuTeleDriveController.getInstance().renameFile(currentAccount, activeDialogId, file.messageId, name);
                    reloadData(false);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Renamed in index.json").show();
                }
            });
            builder.setNegativeButton("Cancel", null);
            hostFragment.showDialog(builder.create());
        }

        private void showEditMessageOrIndexDialog(TeleDriveFile file) {
            AyuTeleDriveController.getInstance().resolveMessageObject(currentAccount, activeDialogId, file.messageId, msgObj -> {
                if (hostFragment.getParentActivity() == null) return;
                String currentText = (msgObj != null && msgObj.messageOwner != null && !TextUtils.isEmpty(msgObj.messageOwner.message))
                        ? msgObj.messageOwner.message
                        : file.name;
                AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
                builder.setTitle("Edit Item (#" + file.messageId + ")");
                EditTextBoldCursor input = createDialogInput("New text / caption", currentText);
                input.setSingleLine(false);
                builder.setView(wrapDialogInput(input));
                builder.setPositiveButton("Save to Index & Telegram", (d, w) -> {
                    String newText = input.getText().toString().trim();
                    if (TextUtils.isEmpty(newText)) return;
                    AyuTeleDriveController.getInstance().renameFile(currentAccount, activeDialogId, file.messageId, newText);
                    if (msgObj != null) {
                        TLRPC.Chat chat = activeDialogId < 0 ? MessagesController.getInstance(currentAccount).getChat(-activeDialogId) : null;
                        if (msgObj.canEditMessage(chat)) {
                            SendMessagesHelper.getInstance(currentAccount).editMessage(msgObj, newText, true, hostFragment, null, 0);
                        }
                    }
                    reloadData(false);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Updated in TeleDrive Index").show();
                });
                builder.setNegativeButton("Cancel", null);
                hostFragment.showDialog(builder.create());
            });
        }

        private void showForwardItemsDialog(List<TeleDriveFile> itemsToForward) {
            if (hostFragment.getParentActivity() == null || itemsToForward == null || itemsToForward.isEmpty()) return;
            List<TeleDriveModels.ChatSummary> chats = AyuTeleDriveController.getInstance().listAvailableChats(currentAccount);
            List<CharSequence> labels = new ArrayList<>();
            List<Integer> icons = new ArrayList<>();
            for (TeleDriveModels.ChatSummary c : chats) {
                labels.add(c.title);
                icons.add(TeleDriveModels.CHAT_KIND_SAVED.equals(c.kind) ? R.drawable.msg_saved : R.drawable.msg_channel);
            }
            int[] iconArr = new int[icons.size()];
            for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);

            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Forward " + itemsToForward.size() + " item(s) to:");
            builder.setItems(labels.toArray(new CharSequence[0]), iconArr, (d, which) -> {
                long targetDialogId = chats.get(which).dialogId;
                ArrayList<MessageObject> resolvedList = new ArrayList<>();
                final int total = itemsToForward.size();
                final int[] done = {0};
                for (TeleDriveFile tf : itemsToForward) {
                    AyuTeleDriveController.getInstance().resolveMessageObject(currentAccount, tf.chatId, tf.messageId, msgObj -> {
                        done[0]++;
                        if (msgObj != null) {
                            resolvedList.add(msgObj);
                        }
                        if (done[0] == total && !resolvedList.isEmpty()) {
                            resolvedList.sort(Comparator.comparingInt(MessageObject::getId));
                            SendMessagesHelper.getInstance(currentAccount).sendMessage(resolvedList, targetDialogId, false, false, true, 0);
                            BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.forward, "Forwarded " + resolvedList.size() + " item(s) to " + chats.get(which).title).show();
                        }
                    });
                }
            });
            hostFragment.showDialog(builder.create());
        }

        public void showUploadFileDialog() {
            if (hostFragment.getParentActivity() == null) return;
            long targetUploadChat = (workspace != null && workspace.canUpload) ? activeDialogId : AyuTeleDriveController.getInstance().getLinkedVaultChatId(activeDialogId);
            if (targetUploadChat == 0L || !AyuTeleDriveController.getInstance().canPostToDialog(currentAccount, targetUploadChat)) {
                BulletinFactory.of(hostFragment).createErrorBulletin("Link a Vault channel first to upload files").show();
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Upload File to Current Folder");
            EditTextBoldCursor input = createDialogInput("/storage/emulated/0/Download/...", "/storage/emulated/0/Download/");
            builder.setView(wrapDialogInput(input));
            builder.setPositiveButton("Upload", (d, w) -> {
                String path = input.getText().toString().trim();
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    ArrayList<String> list = new ArrayList<>();
                    list.add(f.getAbsolutePath());
                    AyuTeleDriveController.getInstance().uploadFilesToFolder(currentAccount, targetUploadChat, currentFolderId, list);
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Uploading " + f.getName() + " to folder...").show();
                } else {
                    BulletinFactory.of(hostFragment).createErrorBulletin("File not found at path").show();
                }
            });
            builder.setNegativeButton("Cancel", null);
            hostFragment.showDialog(builder.create());
        }

        public void showSortDialog() {
            if (hostFragment.getParentActivity() == null) return;
            CharSequence[] items = new CharSequence[]{
                    "Sort by Name (A-Z)",
                    "Sort by Newest First",
                    "Sort by Oldest First",
                    "Sort by Largest Size"
            };
            int[] icons = new int[]{
                    R.drawable.msg_reorder,
                    R.drawable.msg_calendar2,
                    R.drawable.msg_calendar2,
                    R.drawable.msg_sendfile
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Sort Folders & Uncategorized Items");
            builder.setItems(items, icons, (d, which) -> {
                if (which == 0) sortMode = SORT_NAME_ASC;
                else if (which == 1) sortMode = SORT_DATE_DESC;
                else if (which == 2) sortMode = SORT_DATE_ASC;
                else if (which == 3) sortMode = SORT_SIZE_DESC;
                sortFoldersAndFiles();
                refreshListOnly();
            });
            hostFragment.showDialog(builder.create());
        }

        public void showChannelSwitcherDialog() {
            if (hostFragment.getParentActivity() == null) return;
            List<TeleDriveModels.ChatSummary> chats = AyuTeleDriveController.getInstance().listAvailableChats(currentAccount);
            List<CharSequence> items = new ArrayList<>();
            List<Integer> icons = new ArrayList<>();
            items.add("Create New TeleDrive Vault Channel");
            icons.add(R.drawable.msg_addfolder);
            for (TeleDriveModels.ChatSummary c : chats) {
                items.add(c.title + " (" + c.kind + ")");
                icons.add(c.isVault ? R.drawable.msg_folders : (TeleDriveModels.CHAT_KIND_SAVED.equals(c.kind) ? R.drawable.msg_saved : R.drawable.msg_channel));
            }
            int[] iconArr = new int[icons.size()];
            for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);

            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle("Choose TeleDrive Workspace");
            builder.setItems(items.toArray(new CharSequence[0]), iconArr, (d, which) -> {
                if (which == 0) {
                    showSetupVaultForChannelDialog(hostFragment, currentAccount, activeDialogId, () -> reloadData(true));
                } else {
                    TeleDriveModels.ChatSummary picked = chats.get(which - 1);
                    activeDialogId = picked.dialogId;
                    currentFolderId = TeleDriveModels.ROOT_ID;
                    reloadData(true);
                }
            });
            hostFragment.showDialog(builder.create());
        }

        public void showVaultSettingsSheet() {
            if (hostFragment.getParentActivity() == null || workspace == null) return;
            CharSequence[] options = new CharSequence[]{
                    "Sync Now (Scan messages & sync index.json)",
                    "Full Rescan Channel History",
                    "Change / Create Vault Channel for index.json",
                    "Upload File into Current Folder",
                    "Export & Share index.json"
            };
            int[] icons = new int[]{
                    R.drawable.msg_retry,
                    R.drawable.msg_search,
                    R.drawable.msg_folders,
                    R.drawable.msg_sendfile,
                    R.drawable.msg_share
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(hostFragment.getParentActivity());
            builder.setTitle(workspace.title + " • TeleDrive Vault");
            builder.setItems(options, icons, (d, which) -> {
                var ctrl = AyuTeleDriveController.getInstance();
                if (which == 0) {
                    ctrl.syncWorkspace(currentAccount, activeDialogId, false, () -> reloadData(false));
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Syncing workspace...").show();
                } else if (which == 1) {
                    ctrl.syncWorkspace(currentAccount, activeDialogId, true, () -> reloadData(false));
                    BulletinFactory.of(hostFragment).createSimpleBulletin(R.raw.info, "Full channel rescan started...").show();
                } else if (which == 2) {
                    showSetupVaultForChannelDialog(hostFragment, currentAccount, activeDialogId, () -> reloadData(true));
                } else if (which == 3) {
                    showUploadFileDialog();
                } else if (which == 4) {
                    try {
                        File exported = ctrl.exportIndexToFile(activeDialogId);
                        var uri = FileProvider.getUriForFile(hostFragment.getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", exported);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/json");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        hostFragment.getParentActivity().startActivity(Intent.createChooser(share, "Share TeleDrive index.json"));
                    } catch (Exception e) {
                        BulletinFactory.of(hostFragment).createErrorBulletin("Export failed: " + e.getMessage()).show();
                    }
                }
            });
            hostFragment.showDialog(builder.create());
        }

        private EditTextBoldCursor createDialogInput(String hint, String initial) {
            EditTextBoldCursor input = new EditTextBoldCursor(hostFragment.getParentActivity());
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
            input.setHint(hint);
            input.setText(initial);
            input.setSingleLine(true);
            input.setSelection(input.length());
            return input;
        }

        private FrameLayout wrapDialogInput(EditTextBoldCursor input) {
            FrameLayout container = new FrameLayout(hostFragment.getParentActivity());
            container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
            container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42));
            return container;
        }

        // =====================================================================
        // DriveAdapter (Top: Sorted Virtual Folders, Bottom: Uncategorized Messages/Files)
        // =====================================================================

        private class DriveAdapter extends RecyclerListView.SelectionAdapter {
            private final Context mContext;

            public DriveAdapter(Context context) {
                this.mContext = context;
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                int type = holder.getItemViewType();
                return type == 2 || type == 3 || type == 4;
            }

            @Override
            public int getItemCount() {
                return rowCount;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view;
                if (viewType == 0) {
                    view = new ShadowSectionCell(mContext);
                } else if (viewType == 1) {
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                } else if (viewType == 5) {
                    view = new TextInfoPrivacyCell(mContext);
                } else {
                    view = new TextCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                }
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                int type = holder.getItemViewType();
                if (type == 1) {
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == foldersHeaderRow) {
                        header.setText("SORTED FOLDERS (" + subFolders.size() + ")");
                    } else if (position == filesHeaderRow) {
                        if (TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                            header.setText("UNCATEGORIZED MESSAGES & FILES (" + files.size() + ")");
                        } else {
                            header.setText("ITEMS IN FOLDER (" + files.size() + ")");
                        }
                    }
                } else if (type == 2) {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == breadcrumbRow) {
                        String path = buildBreadcrumbPath();
                        long vaultId = AyuTeleDriveController.getInstance().getLinkedVaultChatId(activeDialogId);
                        String vaultLabel = vaultId != 0L
                                ? ("Vault: " + AyuTeleDriveController.getInstance().getDialogTitle(currentAccount, vaultId))
                                : "Local Index";
                        int icon = canNavigateUp() ? R.drawable.ic_ab_back : R.drawable.msg_folders;
                        cell.setTextAndValueAndIcon(path, canNavigateUp() ? "Back Up" : vaultLabel, icon, true);
                    } else if (position == newFolderActionRow) {
                        cell.setTextAndIcon("Create New Folder", R.drawable.msg_addfolder, true);
                    } else if (position == sortActionRow) {
                        cell.setTextAndValueAndIcon("Sort Order", getSortLabel(), R.drawable.msg_reorder, true);
                    } else if (position == syncActionRow) {
                        cell.setTextAndValueAndIcon("Sync Channel & Vault Index", "Tap to Refresh", R.drawable.msg_retry, false);
                    }
                } else if (type == 3) {
                    TextCell cell = (TextCell) holder.itemView;
                    TeleDriveFolder folder = subFolders.get(position - foldersStartRow);
                    boolean divider = position != foldersEndRow - 1;
                    int count = folderItemCounts.getOrDefault(folder.id, 0);
                    cell.setTextAndValueAndIcon(folder.name, count + " items", R.drawable.msg_folders, divider);
                } else if (type == 4) {
                    TextCell cell = (TextCell) holder.itemView;
                    TeleDriveFile file = files.get(position - filesStartRow);
                    boolean divider = position != filesEndRow - 1;
                    boolean cached = !TextUtils.isEmpty(file.localPath) && new File(file.localPath).exists();
                    String meta;
                    if (TeleDriveModels.FILE_KIND_MESSAGE.equals(file.kind)) {
                        meta = "#" + file.messageId + " • Message";
                    } else {
                        meta = AndroidUtilities.formatFileSize(file.size) + (cached ? " • Cached" : (" • #" + file.messageId));
                    }
                    int icon = R.drawable.msg_sendfile;
                    if (TeleDriveModels.FILE_KIND_PHOTO.equals(file.kind)) icon = R.drawable.msg_photos;
                    else if (TeleDriveModels.FILE_KIND_VIDEO.equals(file.kind)) icon = R.drawable.msg_video;
                    else if (TeleDriveModels.FILE_KIND_AUDIO.equals(file.kind) || TeleDriveModels.FILE_KIND_VOICE.equals(file.kind)) icon = R.drawable.msg_voice_bluetooth;
                    else if (TeleDriveModels.FILE_KIND_MESSAGE.equals(file.kind)) icon = R.drawable.msg_message;
                    cell.setTextAndValueAndIcon(file.name != null ? file.name : ("Item #" + file.messageId), meta, icon, divider);
                } else if (type == 5) {
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    if (TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                        info.setText("No uncategorized messages or files. Tap 'Sync Channel & Vault Index' to scan messages, or 'Create New Folder' above.");
                    } else {
                        info.setText("This folder is empty. Long-press any uncategorized message or file at the root level and select 'Move to Folder'.");
                    }
                }
            }

            @Override
            public int getItemViewType(int position) {
                if (position == actionsDividerRow || position == foldersDividerRow) {
                    return 0;
                } else if (position == foldersHeaderRow || position == filesHeaderRow) {
                    return 1;
                } else if (position == breadcrumbRow || position == newFolderActionRow || position == sortActionRow || position == syncActionRow) {
                    return 2;
                } else if (foldersStartRow != -1 && position >= foldersStartRow && position < foldersEndRow) {
                    return 3;
                } else if (filesStartRow != -1 && position >= filesStartRow && position < filesEndRow) {
                    return 4;
                } else if (position == emptyInfoRow) {
                    return 5;
                }
                return 2;
            }
        }
    }
}