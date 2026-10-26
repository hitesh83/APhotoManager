/*
 * Copyright (c) 2017 by k3b.
 *
 * This file is part of AndroFotoFinder / #APhotoManager.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.android.util;

import java.io.File;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;
import java.io.File;

public class DatabaseContext extends ContextWrapper {
    private static final String DEBUG_CONTEXT = "DatabaseContext";

    public DatabaseContext(final Context base) {
        super(base);
    }

    @Override
    public File getDatabasePath(final String name) {
        // Fallback to app-internal databases directory for modern Android compatibility,
        // or keep external path only if permitted/legacy.
        File result;
        try {
            final File sdcard = Environment.getExternalStorageDirectory();
            String dbfile = sdcard.getAbsolutePath() + File.separator + "databases"
                    + File.separator + name;
            if (!dbfile.endsWith(".db")) {
                dbfile += ".db";
            }
            result = new File(dbfile);
            if (!result.getParentFile().exists()) {
                result.getParentFile().mkdirs();
            }
        } catch (Exception e) {
            // Fallback to standard internal storage if external root write fails
            result = super.getDatabasePath(name);
        }

        // If directory creation failed (e.g., due to Scoped Storage on Android 10+), fallback to internal
        if (result.getParentFile() != null && !result.getParentFile().exists() && !result.getParentFile().mkdirs()) {
            result = super.getDatabasePath(name);
        }

        return result;
    }

    /** this version is called for android devices < api-11 */
    @Override
    public SQLiteDatabase openOrCreateDatabase(final String name,
                                               final int mode, final SQLiteDatabase.CursorFactory factory) {
        final SQLiteDatabase result = SQLiteDatabase.openOrCreateDatabase(
                this.getDatabasePath(name), null);
        // SQLiteDatabase result = super.openOrCreateDatabase(name, mode,
        // factory);
        if (Log.isLoggable(DatabaseContext.DEBUG_CONTEXT, Log.INFO)) {
            Log.i(DatabaseContext.DEBUG_CONTEXT, "openOrCreateDatabase(" + name
                    + ",,) = " + result.getPath());
        }
        return result;
    }

    /** this version is called for android devices >= api-11 */
    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        return openOrCreateDatabase(name,mode, factory);
    }
}
