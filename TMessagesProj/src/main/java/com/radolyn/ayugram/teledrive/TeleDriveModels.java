/*
 * TeleDrive Index JSON Models & Constants for AyuGram4A
 * 100% compatible with TeleDrive-2 index.json (#TeleDriveIndex) format
 */

package com.radolyn.ayugram.teledrive;

import java.util.ArrayList;
import java.util.List;

public class TeleDriveModels {
    public static final String ROOT_ID = "root";
    public static final String ROOT_LABEL = "Root Storage";
    public static final String INDEX_FILE_NAME = "index.json";
    public static final String INDEX_CAPTION_TAG = "#TeleDriveIndex";
    public static final String DEFAULT_VAULT_TITLE = "TeleDrive_Vault";

    public static final String INDEX_MODE_CLOUD = "CLOUD";
    public static final String INDEX_MODE_LOCAL = "LOCAL";

    public static final String CHAT_KIND_CHANNEL = "CHANNEL";
    public static final String CHAT_KIND_GROUP = "GROUP";
    public static final String CHAT_KIND_SAVED = "SAVED";

    public static final String FILE_KIND_DOCUMENT = "DOCUMENT";
    public static final String FILE_KIND_PHOTO = "PHOTO";
    public static final String FILE_KIND_VIDEO = "VIDEO";
    public static final String FILE_KIND_AUDIO = "AUDIO";
    public static final String FILE_KIND_ANIMATION = "ANIMATION";
    public static final String FILE_KIND_VOICE = "VOICE";
    public static final String FILE_KIND_MESSAGE = "MESSAGE";

    public static class IndexDocument {
        public int schema = 1;
        public String app = "TeleDrive";
        public long contentChatId;
        public String contentTitle = "";
        public long revision;
        public long updatedAt;
        public List<IndexFolder> folders = new ArrayList<>();
        public List<IndexFile> files = new ArrayList<>();
    }

    public static class IndexFolder {
        public String id;
        public String parentId = ROOT_ID;
        public String name;
        public long createdAt;
        public long updatedAt;

        public IndexFolder() {
        }

        public IndexFolder(String id, String parentId, String name, long createdAt, long updatedAt) {
            this.id = id;
            this.parentId = parentId != null ? parentId : ROOT_ID;
            this.name = name;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static class IndexFile {
        public long messageId;
        public String folderId = ROOT_ID;
        public String name;
        public long size;
        public String mime;
        public String kind = FILE_KIND_DOCUMENT;
        public int date;
        public long updatedAt;

        public IndexFile() {
        }

        public IndexFile(long messageId, String folderId, String name, long size, String mime, String kind, int date, long updatedAt) {
            this.messageId = messageId;
            this.folderId = folderId != null ? folderId : ROOT_ID;
            this.name = name;
            this.size = size;
            this.mime = mime;
            this.kind = kind != null ? kind : FILE_KIND_DOCUMENT;
            this.date = date;
            this.updatedAt = updatedAt;
        }
    }

    public static class ChatSummary {
        public long dialogId;
        public String title;
        public String kind;
        public boolean isVault;
        public boolean canUpload;

        public ChatSummary(long dialogId, String title, String kind, boolean isVault, boolean canUpload) {
            this.dialogId = dialogId;
            this.title = title;
            this.kind = kind;
            this.isVault = isVault;
            this.canUpload = canUpload;
        }
    }
}
