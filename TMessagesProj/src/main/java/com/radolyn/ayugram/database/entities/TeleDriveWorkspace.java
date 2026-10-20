/*
 * TeleDrive Virtual File System Entity for AyuGram4A
 */

package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "teledrive_workspaces")
public class TeleDriveWorkspace {
    @PrimaryKey
    public long chatId;

    public String title;
    public String kind;
    public boolean canUpload;
    public String indexMode;
    public long indexChatId;
    public int indexMessageId;
    public long indexRevision;
    public long localSeq;
    public long syncedSeq;
    public int newestScannedMessageId;
    public long lastSyncAt;
    public long createdAt;

    public boolean needsPush() {
        return localSeq > syncedSeq;
    }
}
