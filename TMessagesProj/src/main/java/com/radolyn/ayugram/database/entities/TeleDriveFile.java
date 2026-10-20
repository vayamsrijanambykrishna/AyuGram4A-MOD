/*
 * TeleDrive Virtual File Entity for AyuGram4A
 */

package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "teledrive_files",
        primaryKeys = {"chatId", "messageId"},
        indices = {@Index({"chatId", "folderId"})}
)
public class TeleDriveFile {
    public long chatId;
    public int messageId;
    public String folderId = "root";
    public String name;
    public String mimeType;
    public long size;
    public String kind;
    public int date;
    public int width;
    public int height;
    public int duration;
    public String localPath;
    public boolean isHidden;
    public boolean customized;
    public long updatedAt;

    public String key() {
        return chatId + ":" + messageId;
    }
}
