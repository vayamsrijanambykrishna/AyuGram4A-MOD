/*
 * Native AyuGram UI for TeleDrive Virtual Folder & File Manager
 * Uses AyuGram's BaseFragment, RecyclerListView, ChatAttachAlertDocumentLayout, PhotoViewer, MediaController & FileLoader.
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
import android.widget.LinearLayout;
import android.widget.TextView;
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

public class AyuTeleDriveActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SWITCH_CHANNEL = 1;
    private static final int MENU_NEW_FOLDER = 2;
    private static final int MENU_UPLOAD_FILE = 3;
    private static final int MENU_SORT = 4;
    private static final int MENU_VAULT_SETTINGS = 5;

    private static final int SORT_DATE = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_SIZE = 2;

    private long activeDialogId;
    private String currentFolderId = TeleDriveModels.ROOT_ID;
    private int sortMode = SORT_DATE;

    private TeleDriveWorkspace workspace;
    private List<TeleDriveFolder> allFolders = new ArrayList<>();
    private List<TeleDriveFolder> subFolders = new ArrayList<>();
    private List<TeleDriveFile> files = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int breadcrumbRow;
    private int actionsSectionRow;
    private int newFolderActionRow;
    private int uploadFileActionRow;
    private int actionsDividerRow;
    private int foldersHeaderRow;
    private int foldersStartRow;
    private int foldersEndRow;
    private int filesHeaderRow;
    private int filesStartRow;
    private int filesEndRow;
    private int emptyInfoRow;
    private int rowCount;

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
        reloadData(true);
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
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long did = (Long) args[0];
            if (did == activeDialogId) {
                reloadData(true);
            }
        }
    }

    private void reloadData(boolean triggerSync) {
        var controller = AyuTeleDriveController.getInstance();
        workspace = controller.getOrCreateWorkspace(currentAccount, activeDialogId);
        allFolders = controller.getDao().getAllFolders(activeDialogId);
        subFolders = controller.getDao().getSubFolders(activeDialogId, currentFolderId);
        files = controller.getDao().getFilesInFolder(activeDialogId, currentFolderId);
        sortFilesList();
        updateRows();
        updateActionBarTitle();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (triggerSync) {
            controller.syncWorkspace(currentAccount, activeDialogId, false, () -> reloadData(false));
        }
    }

    private void sortFilesList() {
        if (files == null) return;
        if (sortMode == SORT_NAME) {
            files.sort((a, b) -> (a.name != null ? a.name : "").compareToIgnoreCase(b.name != null ? b.name : ""));
        } else if (sortMode == SORT_SIZE) {
            files.sort((a, b) -> Long.compare(b.size, a.size));
        } else {
            files.sort((a, b) -> Integer.compare(b.date, a.date));
        }
    }

    private void updateRows() {
        rowCount = 0;
        breadcrumbRow = rowCount++;
        actionsSectionRow = rowCount++;
        newFolderActionRow = rowCount++;
        uploadFileActionRow = rowCount++;
        actionsDividerRow = rowCount++;

        if (!subFolders.isEmpty()) {
            foldersHeaderRow = rowCount++;
            foldersStartRow = rowCount;
            rowCount += subFolders.size();
            foldersEndRow = rowCount;
        } else {
            foldersHeaderRow = -1;
            foldersStartRow = -1;
            foldersEndRow = -1;
        }

        if (!files.isEmpty()) {
            filesHeaderRow = rowCount++;
            filesStartRow = rowCount;
            rowCount += files.size();
            filesEndRow = rowCount;
        } else {
            filesHeaderRow = -1;
            filesStartRow = -1;
            filesEndRow = -1;
        }

        if (subFolders.isEmpty() && files.isEmpty()) {
            emptyInfoRow = rowCount++;
        } else {
            emptyInfoRow = -1;
        }
    }

    private void updateActionBarTitle() {
        if (actionBar == null) return;
        String wsTitle = workspace != null && !TextUtils.isEmpty(workspace.title) ? workspace.title : "TeleDrive";
        actionBar.setTitle(wsTitle);
        int totalItems = subFolders.size() + files.size();
        String modeLabel = workspace != null && TeleDriveModels.INDEX_MODE_CLOUD.equals(workspace.indexMode) ? "Cloud Index" : "Local Index";
        actionBar.setSubtitle(buildBreadcrumbPath() + " • " + totalItems + " items (" + modeLabel + ")");
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
        return TextUtils.join(" > ", names);
    }

    @Override
    public boolean onBackPressed() {
        if (!TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
            navigateUpFolder();
            return false;
        }
        return super.onBackPressed();
    }

    private void navigateUpFolder() {
        if (TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
            finishFragment();
            return;
        }
        TeleDriveFolder current = AyuTeleDriveController.getInstance().getDao().getFolder(currentFolderId);
        currentFolderId = (current != null && !TextUtils.isEmpty(current.parentId)) ? current.parentId : TeleDriveModels.ROOT_ID;
        reloadData(false);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateActionBarTitle();
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                        navigateUpFolder();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_SWITCH_CHANNEL) {
                    showChannelSwitcherDialog();
                } else if (id == MENU_NEW_FOLDER) {
                    showCreateFolderDialog();
                } else if (id == MENU_UPLOAD_FILE) {
                    showUploadFileDialog();
                } else if (id == MENU_SORT) {
                    showSortDialog();
                } else if (id == MENU_VAULT_SETTINGS) {
                    showVaultSettingsSheet();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem moreItem = menu.addItem(0, R.drawable.ic_ab_other);
        moreItem.addSubItem(MENU_SWITCH_CHANNEL, R.drawable.msg_channel, "Switch Channel / New Vault");
        moreItem.addSubItem(MENU_NEW_FOLDER, R.drawable.msg_folders, "New Folder");
        moreItem.addSubItem(MENU_UPLOAD_FILE, R.drawable.msg_sendfile, "Upload File to Folder");
        moreItem.addSubItem(MENU_SORT, R.drawable.msg_reorder, "Sort Files");
        moreItem.addSubItem(MENU_VAULT_SETTINGS, R.drawable.msg_settings, "Index & Sync Options");

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter = new ListAdapter(context));

        listView.setOnItemClickListener((view, position) -> {
            if (position == breadcrumbRow) {
                if (!TeleDriveModels.ROOT_ID.equals(currentFolderId)) {
                    navigateUpFolder();
                } else {
                    showChannelSwitcherDialog();
                }
            } else if (position == newFolderActionRow) {
                showCreateFolderDialog();
            } else if (position == uploadFileActionRow) {
                showUploadFileDialog();
            } else if (foldersStartRow != -1 && position >= foldersStartRow && position < foldersEndRow) {
                TeleDriveFolder folder = subFolders.get(position - foldersStartRow);
                currentFolderId = folder.id;
                reloadData(false);
            } else if (filesStartRow != -1 && position >= filesStartRow && position < filesEndRow) {
                TeleDriveFile file = files.get(position - filesStartRow);
                AyuTeleDriveController.getInstance().openOrDownloadFile(this, currentAccount, file, () -> {
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
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

        return fragmentView;
    }

    // =========================================================================
    // Dialogs for Folders, Files, Channels, and Cloud Index Sync
    // =========================================================================

    private void showCreateFolderDialog() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("New Folder");

        EditTextBoldCursor input = createDialogInput("Folder name", "");
        FrameLayout container = wrapDialogInput(input);
        builder.setView(container);

        builder.setPositiveButton("Create", (d, w) -> {
            String name = input.getText().toString().trim();
            if (!TextUtils.isEmpty(name)) {
                AyuTeleDriveController.getInstance().createFolder(currentAccount, activeDialogId, currentFolderId, name);
                reloadData(false);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showFolderOptionsDialog(TeleDriveFolder folder) {
        if (getParentActivity() == null) return;
        CharSequence[] items = new CharSequence[]{"Open Folder", "Rename Folder", "Delete Folder (Keep files)"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(folder.name);
        builder.setItems(items, (d, which) -> {
            if (which == 0) {
                currentFolderId = folder.id;
                reloadData(false);
            } else if (which == 1) {
                showRenameFolderDialog(folder);
            } else if (which == 2) {
                AyuTeleDriveController.getInstance().deleteFolder(currentAccount, activeDialogId, folder.id);
                reloadData(false);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Folder deleted; files moved up one level").show();
            }
        });
        showDialog(builder.create());
    }

    private void showRenameFolderDialog(TeleDriveFolder folder) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
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
        showDialog(builder.create());
    }

    private void showFileOptionsDialog(TeleDriveFile file) {
        if (getParentActivity() == null) return;
        CharSequence[] items = new CharSequence[]{
                "Open / View in AyuGram",
                "Move to Folder",
                "Rename File",
                "Save to Downloads/TeleDrive",
                "Remove / Delete File"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(file.name);
        builder.setItems(items, (d, which) -> {
            if (which == 0) {
                AyuTeleDriveController.getInstance().openOrDownloadFile(this, currentAccount, file, null);
            } else if (which == 1) {
                showMoveFileDialog(file);
            } else if (which == 2) {
                showRenameFileDialog(file);
            } else if (which == 3) {
                AyuTeleDriveController.getInstance().exportFileToDownloads(this, currentAccount, file);
            } else if (which == 4) {
                AyuTeleDriveController.getInstance().deleteFiles(currentAccount, activeDialogId, Collections.singletonList(file.messageId), true);
                reloadData(false);
            }
        });
        showDialog(builder.create());
    }

    private void showMoveFileDialog(TeleDriveFile file) {
        List<TeleDriveFolder> folders = AyuTeleDriveController.getInstance().getDao().getAllFolders(activeDialogId);
        List<String> targetIds = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();

        targetIds.add(TeleDriveModels.ROOT_ID);
        labels.add("📁 " + TeleDriveModels.ROOT_LABEL + (TeleDriveModels.ROOT_ID.equals(file.folderId) ? " (current)" : ""));

        for (TeleDriveFolder f : folders) {
            targetIds.add(f.id);
            labels.add("📁 " + f.name + (f.id.equals(file.folderId) ? " (current)" : ""));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Move \"" + file.name + "\" to:");
        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            String targetFolder = targetIds.get(which);
            AyuTeleDriveController.getInstance().moveFiles(currentAccount, activeDialogId, Collections.singletonList(file.messageId), targetFolder);
            reloadData(false);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Moved to folder").show();
        });
        showDialog(builder.create());
    }

    private void showRenameFileDialog(TeleDriveFile file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Rename File");
        EditTextBoldCursor input = createDialogInput("File name", file.name);
        builder.setView(wrapDialogInput(input));
        builder.setPositiveButton("Save", (d, w) -> {
            String name = input.getText().toString().trim();
            if (!TextUtils.isEmpty(name)) {
                AyuTeleDriveController.getInstance().renameFile(currentAccount, activeDialogId, file.messageId, name);
                reloadData(false);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showUploadFileDialog() {
        if (getParentActivity() == null) return;
        if (workspace != null && !workspace.canUpload) {
            BulletinFactory.of(this).createErrorBulletin("You don't have permission to upload to this channel").show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Upload File to Current Folder");
        EditTextBoldCursor input = createDialogInput("/storage/emulated/0/Download/...", "/storage/emulated/0/Download/");
        builder.setView(wrapDialogInput(input));
        builder.setPositiveButton("Upload", (d, w) -> {
            String path = input.getText().toString().trim();
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                ArrayList<String> list = new ArrayList<>();
                list.add(f.getAbsolutePath());
                AyuTeleDriveController.getInstance().uploadFilesToFolder(currentAccount, activeDialogId, currentFolderId, list);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Uploading " + f.getName() + " to folder...").show();
            } else {
                BulletinFactory.of(this).createErrorBulletin("File not found at path").show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showSortDialog() {
        CharSequence[] items = new CharSequence[]{"Newest first", "Name (A-Z)", "Largest size first"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Sort Files");
        builder.setItems(items, (d, which) -> {
            sortMode = which;
            sortFilesList();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    private void showChannelSwitcherDialog() {
        if (getParentActivity() == null) return;
        List<TeleDriveModels.ChatSummary> chats = AyuTeleDriveController.getInstance().listAvailableChats(currentAccount);
        List<CharSequence> items = new ArrayList<>();
        items.add("➕ Create New TeleDrive_Vault Channel");
        for (TeleDriveModels.ChatSummary c : chats) {
            String prefix = c.isVault ? "🔐 " : (TeleDriveModels.CHAT_KIND_SAVED.equals(c.kind) ? "⭐ " : "📢 ");
            items.add(prefix + c.title + " (" + c.kind + ")");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Choose TeleDrive Workspace");
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            if (which == 0) {
                showCreateVaultChannelDialog();
            } else {
                TeleDriveModels.ChatSummary picked = chats.get(which - 1);
                activeDialogId = picked.dialogId;
                currentFolderId = TeleDriveModels.ROOT_ID;
                reloadData(true);
            }
        });
        showDialog(builder.create());
    }

    private void showCreateVaultChannelDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Create TeleDrive Vault Channel");
        EditTextBoldCursor input = createDialogInput("Vault channel name", TeleDriveModels.DEFAULT_VAULT_TITLE);
        builder.setView(wrapDialogInput(input));
        builder.setPositiveButton("Create", (d, w) -> {
            String title = input.getText().toString().trim();
            AyuTeleDriveController.getInstance().createVaultChannel(currentAccount, title, newDialogId -> {
                if (newDialogId != 0L) {
                    activeDialogId = newDialogId;
                    currentFolderId = TeleDriveModels.ROOT_ID;
                    reloadData(true);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Created vault channel!").show();
                } else {
                    BulletinFactory.of(this).createErrorBulletin("Failed to create channel").show();
                }
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showVaultSettingsSheet() {
        if (getParentActivity() == null || workspace == null) return;
        CharSequence[] options = new CharSequence[]{
                "🔄 Sync Now (Scan new files & sync index.json)",
                "🔍 Full Rescan Channel",
                "☁️ Store index.json in THIS channel",
                "📂 Store index.json in a SEPARATE channel (Channel B)",
                "📱 Keep index LOCAL only on this device (Offline mode)",
                "📤 Export & Share index.json"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(workspace.title + " • Index Settings");
        builder.setItems(options, (d, which) -> {
            var ctrl = AyuTeleDriveController.getInstance();
            if (which == 0) {
                ctrl.syncWorkspace(currentAccount, activeDialogId, false, () -> reloadData(false));
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Syncing workspace...").show();
            } else if (which == 1) {
                ctrl.syncWorkspace(currentAccount, activeDialogId, true, () -> reloadData(false));
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Full channel rescan started...").show();
            } else if (which == 2) {
                ctrl.setIndexChannel(currentAccount, activeDialogId, activeDialogId);
                reloadData(false);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "index.json will sync in this channel").show();
            } else if (which == 3) {
                showPickSeparateIndexChannelDialog();
            } else if (which == 4) {
                ctrl.setLocalOnlyMode(activeDialogId);
                reloadData(false);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Folder index kept locally on device only").show();
            } else if (which == 5) {
                try {
                    File exported = ctrl.exportIndexToFile(activeDialogId);
                    var uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", exported);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/json");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getParentActivity().startActivity(Intent.createChooser(share, "Share TeleDrive index.json"));
                } catch (Exception e) {
                    BulletinFactory.of(this).createErrorBulletin("Export failed: " + e.getMessage()).show();
                }
            }
        });
        showDialog(builder.create());
    }

    private void showPickSeparateIndexChannelDialog() {
        List<TeleDriveModels.ChatSummary> postable = new ArrayList<>();
        List<CharSequence> names = new ArrayList<>();
        for (TeleDriveModels.ChatSummary c : AyuTeleDriveController.getInstance().listAvailableChats(currentAccount)) {
            if (c.canUpload) {
                postable.add(c);
                names.add(c.title);
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Select Channel B for index.json");
        builder.setItems(names.toArray(new CharSequence[0]), (d, which) -> {
            TeleDriveModels.ChatSummary picked = postable.get(which);
            AyuTeleDriveController.getInstance().setIndexChannel(currentAccount, activeDialogId, picked.dialogId);
            reloadData(false);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "index.json now syncs in " + picked.title).show();
        });
        showDialog(builder.create());
    }

    /** Quick static helper so ChatActivity can assign any message to a TeleDrive folder with 1 click! */
    public static void showAssignMessageToFolderDialog(BaseFragment fragment, int accountId, long dialogId, TLRPC.Message message) {
        if (fragment == null || fragment.getParentActivity() == null || message == null) return;
        var ctrl = AyuTeleDriveController.getInstance();
        ctrl.getOrCreateWorkspace(accountId, dialogId);
        List<TeleDriveFolder> folders = ctrl.getDao().getAllFolders(dialogId);

        List<String> ids = new ArrayList<>();
        List<CharSequence> items = new ArrayList<>();
        ids.add("__NEW__");
        items.add("➕ Create New Folder & Save Here");
        ids.add("__OPEN_DRIVE__");
        items.add("🚀 Open TeleDrive for this Chat");
        ids.add(TeleDriveModels.ROOT_ID);
        items.add("📁 " + TeleDriveModels.ROOT_LABEL);

        for (TeleDriveFolder f : folders) {
            ids.add(f.id);
            items.add("📁 " + f.name);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Save to TeleDrive Folder");
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            String chosen = ids.get(which);
            if ("__OPEN_DRIVE__".equals(chosen)) {
                fragment.presentFragment(new AyuTeleDriveActivity(dialogId));
            } else if ("__NEW__".equals(chosen)) {
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

    private EditTextBoldCursor createDialogInput(String hint, String initial) {
        EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
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
        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42));
        return container;
    }

    // =========================================================================
    // ListAdapter
    // =========================================================================

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
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
                if (position == actionsSectionRow) {
                    header.setText("TELEDRIVE WORKSPACE ACTIONS");
                } else if (position == foldersHeaderRow) {
                    header.setText("VIRTUAL FOLDERS (" + subFolders.size() + ")");
                } else if (position == filesHeaderRow) {
                    header.setText("FILES IN THIS FOLDER (" + files.size() + ")");
                }
            } else if (type == 2) {
                TextCell cell = (TextCell) holder.itemView;
                if (position == breadcrumbRow) {
                    String path = "📍 " + buildBreadcrumbPath();
                    String action = TeleDriveModels.ROOT_ID.equals(currentFolderId) ? "Switch Chat" : "Go Up";
                    cell.setTextAndValueAndIcon(path, action, R.drawable.msg_folders, true);
                } else if (position == newFolderActionRow) {
                    cell.setTextAndIcon("Create New Folder", R.drawable.msg_folders, true);
                } else if (position == uploadFileActionRow) {
                    cell.setTextAndIcon("Upload File into Current Folder", R.drawable.msg_sendfile, false);
                }
            } else if (type == 3) {
                TextCell cell = (TextCell) holder.itemView;
                TeleDriveFolder folder = subFolders.get(position - foldersStartRow);
                boolean divider = position != foldersEndRow - 1;
                cell.setTextAndValueAndIcon(folder.name, "Folder", R.drawable.msg_folders, divider);
            } else if (type == 4) {
                TextCell cell = (TextCell) holder.itemView;
                TeleDriveFile file = files.get(position - filesStartRow);
                boolean divider = position != filesEndRow - 1;
                boolean cached = !TextUtils.isEmpty(file.localPath) && new File(file.localPath).exists();
                String meta = AndroidUtilities.formatFileSize(file.size) + (cached ? " • Cached ✓" : " • Cloud");
                int icon = R.drawable.msg_sendfile;
                if (TeleDriveModels.FILE_KIND_PHOTO.equals(file.kind)) icon = R.drawable.msg_photos;
                else if (TeleDriveModels.FILE_KIND_VIDEO.equals(file.kind)) icon = R.drawable.msg_video;
                else if (TeleDriveModels.FILE_KIND_AUDIO.equals(file.kind) || TeleDriveModels.FILE_KIND_VOICE.equals(file.kind)) icon = R.drawable.msg_voice_bluetooth;
                cell.setTextAndValueAndIcon(file.name, meta, icon, divider);
            } else if (type == 5) {
                TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                info.setText("This folder is empty. Tap 'Create New Folder', 'Upload File', or long-press any file in a chat and choose 'Save to TeleDrive Folder'.");
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == actionsDividerRow) {
                return 0;
            } else if (position == actionsSectionRow || position == foldersHeaderRow || position == filesHeaderRow) {
                return 1;
            } else if (position == breadcrumbRow || position == newFolderActionRow || position == uploadFileActionRow) {
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