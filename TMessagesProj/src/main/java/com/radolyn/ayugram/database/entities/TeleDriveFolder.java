/*
 * TeleDrive Virtual Folder Entity for AyuGram4A
 */

package com.radolyn.ayugram.database.entities;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "teledrive_folders",
        indices = {@Index("workspaceChatId"), @Index("parentId")}
)
public class TeleDriveFolder {
    @PrimaryKey
    @NonNull
    public String id = "";

    public long workspaceChatId;
    public String parentId;
    public String name;
    public long createdAt;
    public long updatedAt;
}
