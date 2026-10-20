/*
 * TeleDrive Room DAO for AyuGram4A
 */

package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.radolyn.ayugram.database.entities.TeleDriveFile;
import com.radolyn.ayugram.database.entities.TeleDriveFolder;
import com.radolyn.ayugram.database.entities.TeleDriveWorkspace;

import java.util.List;

@Dao
public interface TeleDriveDao {
    // Workspaces
    @Query("SELECT * FROM teledrive_workspaces WHERE chatId = :chatId")
    TeleDriveWorkspace getWorkspace(long chatId);

    @Query("SELECT * FROM teledrive_workspaces ORDER BY lastSyncAt DESC, createdAt DESC")
    List<TeleDriveWorkspace> getAllWorkspaces();

    @Query("SELECT COUNT(*) FROM teledrive_workspaces WHERE chatId = :chatId")
    int workspaceExists(long chatId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertWorkspace(TeleDriveWorkspace workspace);

    @Query("UPDATE teledrive_workspaces SET localSeq = localSeq + 1 WHERE chatId = :chatId")
    void bumpWorkspace(long chatId);

    @Query("UPDATE teledrive_workspaces SET syncedSeq = :seq WHERE chatId = :chatId")
    void setWorkspaceSynced(long chatId, long seq);

    @Query("UPDATE teledrive_workspaces SET indexMode = :mode, indexChatId = :indexChatId, indexMessageId = :indexMessageId, indexRevision = :revision WHERE chatId = :chatId")
    void setWorkspaceIndex(long chatId, String mode, long indexChatId, int indexMessageId, long revision);

    @Query("UPDATE teledrive_workspaces SET newestScannedMessageId = :messageId, lastSyncAt = :now WHERE chatId = :chatId")
    void setScanCursor(long chatId, int messageId, long now);

    @Query("UPDATE teledrive_workspaces SET newestScannedMessageId = MAX(newestScannedMessageId, :messageId) WHERE chatId = :chatId")
    void raiseCursor(long chatId, int messageId);

    @Query("UPDATE teledrive_workspaces SET canUpload = :canUpload, title = :title WHERE chatId = :chatId")
    void setPermissions(long chatId, boolean canUpload, String title);

    // Folders
    @Query("SELECT * FROM teledrive_folders WHERE workspaceChatId = :chatId ORDER BY name COLLATE NOCASE")
    List<TeleDriveFolder> getAllFolders(long chatId);

    @Query("SELECT * FROM teledrive_folders WHERE workspaceChatId = :chatId AND parentId = :parentId ORDER BY name COLLATE NOCASE")
    List<TeleDriveFolder> getSubFolders(long chatId, String parentId);

    @Query("SELECT * FROM teledrive_folders WHERE id = :id")
    TeleDriveFolder getFolder(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFolder(TeleDriveFolder folder);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFolders(List<TeleDriveFolder> folders);

    @Query("UPDATE teledrive_folders SET name = :name, updatedAt = :now WHERE id = :id")
    void renameFolder(String id, String name, long now);

    @Query("UPDATE teledrive_folders SET parentId = :newParent, updatedAt = :now WHERE workspaceChatId = :chatId AND parentId = :oldParent")
    void reparentChildFolders(long chatId, String oldParent, String newParent, long now);

    @Query("DELETE FROM teledrive_folders WHERE id = :id")
    void deleteFolder(String id);

    @Query("DELETE FROM teledrive_folders WHERE workspaceChatId = :chatId")
    void deleteAllFolders(long chatId);

    // Files
    @Query("SELECT * FROM teledrive_files WHERE chatId = :chatId AND folderId = :folderId AND isHidden = 0 ORDER BY date DESC, messageId DESC")
    List<TeleDriveFile> getFilesInFolder(long chatId, String folderId);

    @Query("SELECT * FROM teledrive_files WHERE chatId = :chatId AND messageId = :messageId")
    TeleDriveFile getFile(long chatId, int messageId);

    @Query("SELECT * FROM teledrive_files WHERE chatId = :chatId")
    List<TeleDriveFile> getAllFiles(long chatId);

    @Query("SELECT messageId FROM teledrive_files WHERE chatId = :chatId")
    List<Integer> getAllFileIds(long chatId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertFilesIgnore(List<TeleDriveFile> files);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFile(TeleDriveFile file);

    @Query("UPDATE teledrive_files SET folderId = :folderId, customized = 1, updatedAt = :now WHERE chatId = :chatId AND messageId IN (:ids)")
    void moveFiles(long chatId, List<Integer> ids, String folderId, long now);

    @Query("UPDATE teledrive_files SET name = :name, customized = 1, updatedAt = :now WHERE chatId = :chatId AND messageId = :messageId")
    void renameFile(long chatId, int messageId, String name, long now);

    @Query("UPDATE teledrive_files SET folderId = :folderId, name = :name, customized = 1, updatedAt = :now WHERE chatId = :chatId AND messageId = :messageId")
    void assignFile(long chatId, int messageId, String folderId, String name, long now);

    @Query("UPDATE teledrive_files SET isHidden = :hidden, updatedAt = :now WHERE chatId = :chatId AND messageId IN (:ids)")
    void setFilesHidden(long chatId, List<Integer> ids, boolean hidden, long now);

    @Query("DELETE FROM teledrive_files WHERE chatId = :chatId AND messageId IN (:ids)")
    void deleteFiles(long chatId, List<Integer> ids);

    @Query("UPDATE teledrive_files SET folderId = :newFolder WHERE chatId = :chatId AND folderId = :oldFolder")
    void reparentFiles(long chatId, String oldFolder, String newFolder);

    @Query("UPDATE teledrive_files SET folderId = 'root' WHERE chatId = :chatId AND folderId != 'root' AND folderId NOT IN (SELECT id FROM teledrive_folders WHERE workspaceChatId = :chatId)")
    void fixOrphanFiles(long chatId);

    @Query("UPDATE teledrive_files SET localPath = :path WHERE chatId = :chatId AND messageId = :messageId")
    void setLocalPath(long chatId, int messageId, String path);
}
