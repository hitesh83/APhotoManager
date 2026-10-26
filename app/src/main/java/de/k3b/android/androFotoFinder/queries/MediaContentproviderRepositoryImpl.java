/*
 * Copyright (c) 2015-2020 by k3b.
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
package de.k3b.android.androFotoFinder.queries;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

import java.util.ArrayList;

import de.k3b.LibGlobal;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.database.QueryParameter;
import de.k3b.io.StringUtils;
import de.k3b.io.VISIBILITY;

/**
 * Static Implementation of Context.getContentResolver()-ContentProvider based media api
 */
public class MediaContentproviderRepositoryImpl {
    public static final String LOG_TAG = FotoSql.LOG_TAG + "Content";
    private static final String MODUL_NAME = MediaContentproviderRepositoryImpl.class.getName();

    public static Cursor createCursorForQuery(
            StringBuilder out_debugMessage, String dbgContext, final Context context,
            QueryParameter parameters, VISIBILITY visibility, CancellationSignal cancellationSignal) {
        if (visibility != null) FotoSql.setWhereVisibility(parameters, visibility);
        return createCursorForQuery(out_debugMessage, dbgContext, context, parameters.toFrom(),
                parameters.toAndroidWhere(),
                parameters.toAndroidParameters(), parameters.toOrderBy(),
                cancellationSignal, parameters.toColumns()
        );
    }

    public static Cursor createCursorForQuery(
            StringBuilder out_debugMessage, String dbgContext, final Context context,
            final String from, final String sqlWhereStatement,
            final String[] sqlWhereParameters, final String sqlSortOrder,
            CancellationSignal cancellationSignal, final String... sqlSelectColumns) {
        Cursor cursor = null;
        Exception exception = null;
        try {
            Uri uri = Uri.parse(from);
            if (context != null && context.getContentResolver() != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    cursor = context.getContentResolver().query(
                            uri, sqlSelectColumns, sqlWhereStatement, sqlWhereParameters, sqlSortOrder, cancellationSignal
                    );
                } else {
                    cursor = context.getContentResolver().query(
                            uri, sqlSelectColumns, sqlWhereStatement, sqlWhereParameters, sqlSortOrder
                    );
                }
            }
        } catch (Exception ex) {
            exception = ex;
            Log.e(LOG_TAG, "Error querying ContentProvider in " + dbgContext, ex);
        } finally {
            if ((exception != null) || Global.debugEnabledSql || (out_debugMessage != null)) {
                final int count = (cursor != null) ? cursor.getCount() : 0;
                StringBuilder message = StringUtils.appendMessage(out_debugMessage, exception,
                        dbgContext, MODUL_NAME +
                                ".createCursorForQuery:\n",
                        QueryParameter.toString(sqlSelectColumns, null, from, sqlWhereStatement,
                                sqlWhereParameters, sqlSortOrder, count));
                if (out_debugMessage == null) {
                    Log.i(LOG_TAG, message.toString(), exception);
                }
            }
        }

        // GUARD: Return an empty MatrixCursor instead of null to prevent getCount() crashes
        if (cursor == null) {
            cursor = new android.database.MatrixCursor(sqlSelectColumns != null ? sqlSelectColumns : new String[]{});
        }

        return cursor;
    }

    public static int execUpdate(String dbgContext, Context context, String path, ContentValues values, VISIBILITY visibility) {
        return exexUpdateImpl(dbgContext, context, values, FotoSql.getFilterExprPathLikeWithVisibility(visibility), new String[]{path});
    }

    public static int exexUpdateImpl(String dbgContext, Context context, ContentValues values, String sqlWhere, String[] selectionArgs) {
        int result = -1;
        Exception excpetion = null;
        try {
            result = context.getContentResolver().update(FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE,
                    values, sqlWhere,
                    selectionArgs);
        } catch (Exception ex) {
            excpetion = ex;
        } finally {
            if ((excpetion != null) || ((dbgContext != null) && (Global.debugEnabledSql || LibGlobal.debugEnabledJpg))) {
                Log.i(LOG_TAG, dbgContext + ":" +
                        MODUL_NAME +
                        ".exexUpdate " + excpetion + "\n" +
                        QueryParameter.toString(null, values.toString(), FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                                sqlWhere, selectionArgs, null, result), excpetion);
            }
        }
        return result;
    }

    public static Long insertOrUpdateMediaDatabase(String dbgContext, Context context,
                                                   String dbUpdateFilterJpgFullPathName,
                                                   ContentValues values, VISIBILITY visibility,
                                                   Long updateSuccessValue) {
        Long result = updateSuccessValue;

        int modifyCount = execUpdate(dbgContext, context, dbUpdateFilterJpgFullPathName,
                values, visibility);

        if (modifyCount == 0) {
            FotoSql.addDateAdded(values);

            Uri uriWithId = execInsert(dbgContext, context, values);
            result = FotoSql.getId(uriWithId);
        }
        return result;
    }

    public static Uri execInsert(String dbgContext, Context context, ContentValues values) {
        Uri providerUri = (null != values.get(FotoSql.SQL_COL_EXT_MEDIA_TYPE)) ? FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE : FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI;

        Uri result = null;
        Exception excpetion = null;
        try {
            result = context.getContentResolver().insert(providerUri, values);
        } catch (Exception ex) {
            excpetion = ex;
        } finally {
            if ((excpetion != null) || Global.debugEnabledSql || LibGlobal.debugEnabledJpg) {
                Log.i(LOG_TAG, dbgContext + ":" +
                        MODUL_NAME +
                        ".execInsert " + excpetion + " " +
                        values.toString() + " => " + result + " " + excpetion, excpetion);
            }
        }
        return result;
    }

    public static int deleteMedia(String dbgContext, Context context, String where, String[] selectionArgs, boolean preventDeleteImageFile) {
        String[] lastSelectionArgs = selectionArgs;
        String lastUsedWhereClause = where;
        int delCount = 0;
        try {
            if (preventDeleteImageFile) {
                ContentValues values = new ContentValues();
                values.put(FotoSql.SQL_COL_PATH, FotoSql.DELETED_FILE_MARKER);
                values.put(FotoSql.SQL_COL_EXT_MEDIA_TYPE, 0);
                exexUpdateImpl(dbgContext + "-a: " +
                                MODUL_NAME +
                                ".deleteMedia: ",
                        context, values, lastUsedWhereClause, lastSelectionArgs);

                lastUsedWhereClause = FotoSql.SQL_COL_PATH + " is null";
                lastSelectionArgs = null;
                delCount = context.getContentResolver()
                        .delete(FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, lastUsedWhereClause, lastSelectionArgs);
            } else {
                delCount = context.getContentResolver()
                        .delete(FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, lastUsedWhereClause, lastSelectionArgs);
            }
        } catch (Exception ex) {
            final String msg = dbgContext + ": Exception in " +
                    MODUL_NAME +
                    ".deleteMedia:\n" +
                    QueryParameter.toString(null, null, FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                            lastUsedWhereClause, lastSelectionArgs, null, -1)
                    + " : " + ex.getMessage();
            Log.e(LOG_TAG, msg, ex);
        }
        return delCount;
    }

    public static ContentValues getDbContent(Context context, final long id) {
        ContentResolver resolver = context.getContentResolver();

        Cursor c = null;
        try {
            c = resolver.query(FotoSqlBase.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, new String[]{"*"}, FotoSql.FILTER_COL_PK, new String[]{"" + id}, null);
            if (c != null && c.moveToNext()) {
                ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(c, values);
                return values;
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, MODUL_NAME +
                    ".getDbContent(id=" + id + ") failed", ex);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}