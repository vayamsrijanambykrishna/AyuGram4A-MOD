/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.dao.TeleDriveDao;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.database.entities.TeleDriveFile;
import com.radolyn.ayugram.database.entities.TeleDriveFolder;
import com.radolyn.ayugram.database.entities.TeleDriveWorkspace;

@Database(entities = {
        EditedMessage.class,
        DeletedMessage.class,
        DeletedMessageReaction.class,
        TeleDriveWorkspace.class,
        TeleDriveFolder.class,
        TeleDriveFile.class
}, version = 22)
public abstract class AyuDatabase extends RoomDatabase {
    public abstract EditedMessageDao editedMessageDao();

    public abstract DeletedMessageDao deletedMessageDao();

    public abstract TeleDriveDao teleDriveDao();
}
