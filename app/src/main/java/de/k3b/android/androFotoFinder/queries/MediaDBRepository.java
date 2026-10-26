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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Log;
 

import java.io.File;
import java.io.InputStream;
import java.sql.Date;

import de.k3b.LibGlobal;
import de.k3b.media.PhotoPropertiesImageReader;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.database.QueryParameter;
import de.k3b.io.AlbumFile;
import de.k3b.io.IProgessListener;
import de.k3b.io.StringUtils;
import de.k3b.io.VISIBILITY;

import static de.k3b.android.androFotoFinder.queries.FotoSql.QUERY_TYPE_UNDEFINED;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_DATE_ADDED;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_DATE_TAKEN;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_EXT_MEDIA_TYPE;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_EXT_RATING;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_EXT_TITLE;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_LAST_MODIFIED;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_LAT;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_LON;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_ORIENTATION;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_PATH;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_PK;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL_SIZE;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_COL__IMPL_DISPLAY_NAME;
import static de.k3b.android.androFotoFinder.queries.FotoSql.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME;
import static de.k3b.android.androFotoFinder.tagDB.TagSql.SQL_COL_EXT_DESCRIPTION;
import static de.k3b.android.androFotoFinder.tagDB.TagSql.SQL_COL_EXT_TAGS;
import static de.k3b.android.androFotoFinder.tagDB.TagSql.SQL_COL_EXT_XMP_LAST_MODIFIED_DATE;

/**
 * Access Media Data through stand alone database-table.
 * <p>
 * Since Android-10 (api 29) using sqLite functions as content-provider-columns is not possible anymore.
 * Therefore apm uses a copy of contentprovider MediaStore.Images with same column names.
 */
public class MediaDBRepository implements IMediaRepositoryApi {
    public static final String LOG_TAG = FotoSql.LOG_TAG + "DB";

    // #155
    public static final boolean debugEnabledSqlRefresh = true;

    private static final String MODUL_NAME = MediaContentproviderRepositoryImpl.class.getName();
    private static String currentUpdateReason = null;
    private static long currentUpdateId = 1;
    private static int transactionNumber = 0;
    private final SQLiteDatabase db;

    // The map query filters by the stored latitude/longitude columns. On Android 10+
    // those columns may be empty even when the original image still contains GPS EXIF.
    // Run the backfill once per process before the first map-marker query.
    private static volatile boolean gpsExifBackfillAttempted = false;

    public MediaDBRepository(SQLiteDatabase db) {
        this.db = db;
    }

    /**
     * Populate missing GPS coordinates from the original photo files so map queries can
     * find them. This method is called from the map's background marker-loading task.
     * It intentionally scans only rows whose coordinates are missing, and caches the
     * successfully parsed coordinates in the local media database.
     */
    public int backfillMissingGpsFromExif(Context context) {
        return backfillMissingGpsFromExif(context, db);
    }

    public static synchronized int backfillMissingGpsFromExif(Context context, SQLiteDatabase db) {
        if (gpsExifBackfillAttempted) {
            Log.i(LOG_TAG, "GPS_DIAG backfill skipped: already attempted in this process");
            return 0;
        }

        Log.i(LOG_TAG, "GPS_DIAG backfill starting; db=" + (db == null ? "null" : db.getPath()));
        Cursor cursor = null;
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        int updated = 0;
        try {
            String missingGps = "(" + SQL_COL_LAT + " IS NULL OR " + SQL_COL_LON + " IS NULL OR ("
                    + SQL_COL_LAT + " = 0 AND " + SQL_COL_LON + " = 0)) AND "
                    + SQL_COL_PATH + " IS NOT NULL AND " + SQL_COL_PATH + " != '' AND "
                    + SQL_COL_EXT_MEDIA_TYPE + " = ?";
            cursor = db.query(Impl.table, new String[]{SQL_COL_PK, SQL_COL_PATH},
                    missingGps, new String[]{String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)},
                    null, null, null);
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
                paths.add(cursor.getString(1));
            }
            Log.i(LOG_TAG, "GPS_DIAG candidate query found " + ids.size() + " rows");
        } catch (Exception ex) {
            Log.e(LOG_TAG, "GPS_DIAG unable to find photos requiring GPS EXIF backfill", ex);
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }

        int withGps = 0;
        for (int i = 0; i < ids.size(); i++) {
            double[] latLon = getOrLoadLatLon(context, db, ids.get(i), paths.get(i));
            if (latLon[0] != 0.0 || latLon[1] != 0.0) {
                updated++;
                withGps++;
            }
        }
        gpsExifBackfillAttempted = true;
        Log.i(LOG_TAG, "GPS_DIAG backfill complete: gpsFound=" + withGps + ", candidates=" + ids.size());
        return updated;
    }

    /**
     * Ensures GPS coordinates are available for a given photo.
     * If missing from the database (0.0), it reads them directly from the raw file EXIF headers on-demand
     * using android.media.ExifInterface via an InputStream to correctly parse rational tags and bypass MediaStore redaction.
     */
    public static double[] getOrLoadLatLon(Context context, SQLiteDatabase db, long photoId, String filePath) {
        double lat = 0.0;
        double lon = 0.0;

        if (filePath == null) {
            return new double[]{lat, lon};
        }

        // 1. Check local database cache first
        Cursor cursor = null;
        try {
            cursor = db.query(Impl.table, new String[]{SQL_COL_LAT, SQL_COL_LON}, SQL_COL_PK + "=?", new String[]{String.valueOf(photoId)}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                lat = cursor.getDouble(0);
                lon = cursor.getDouble(1);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        // 2. If cached coordinates are empty, use APhotoManager's metadata reader first.
        // Unlike android.media.ExifInterface, the existing reader also checks embedded XMP GPS.
        if (lat == 0.0 && lon == 0.0) {
            try {
                PhotoPropertiesImageReader metadataReader = new PhotoPropertiesImageReader();
                try {
                    if (filePath.startsWith("content://")) {
                        Uri uri = Uri.parse(filePath);
                        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                            if (inputStream != null) {
                                metadataReader.load(filePath, inputStream, null, "GPS_DIAG");
                                Double metadataLat = metadataReader.getLatitude();
                                Double metadataLon = metadataReader.getLongitude();
                                if (metadataLat != null && metadataLon != null) {
                                    lat = metadataLat;
                                    lon = metadataLon;
                                }
                            }
                        }
                    } else {
                        File photoFile = new File(filePath);
                        Log.i(LOG_TAG, "GPS_DIAG reading metadata: exists=" + photoFile.exists()
                                + ", readable=" + photoFile.canRead() + ", bytes=" + photoFile.length()
                                + ", path=" + filePath);
                        metadataReader.load(filePath, null, null, "GPS_DIAG");
                        Double metadataLat = metadataReader.getLatitude();
                        Double metadataLon = metadataReader.getLongitude();
                        if (metadataLat != null && metadataLon != null) {
                            lat = metadataLat;
                            lon = metadataLon;
                        }
                    }
                } finally {
                    metadataReader.close();
                }

                // Fallback to Android's parser if the project's metadata reader did not find GPS.
                if (lat == 0.0 && lon == 0.0) {
                    Uri uri = filePath.startsWith("content://")
                            ? Uri.parse(filePath) : Uri.fromFile(new File(filePath));
                    try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                        if (inputStream != null) {
                            android.media.ExifInterface exif = new android.media.ExifInterface(inputStream);
                            float[] latLong = new float[2];
                            if (exif.getLatLong(latLong)) {
                                lat = latLong[0];
                                lon = latLong[1];
                            }
                        }
                    }
                }

                // Cache coordinates so subsequent map queries can use the local DB columns.
                if (lat != 0.0 || lon != 0.0) {
                    ContentValues values = new ContentValues();
                    values.put(SQL_COL_LAT, lat);
                    values.put(SQL_COL_LON, lon);
                    db.update(Impl.table, values, SQL_COL_PK + "=?", new String[]{String.valueOf(photoId)});
                    Log.i(LOG_TAG, "GPS_DIAG GPS extracted and cached photoId=" + photoId
                            + ", lat=" + lat + ", lon=" + lon);
                } else {
                    Log.w(LOG_TAG, "GPS_DIAG no GPS found by metadata-extractor or Android EXIF parser; photoId="
                            + photoId + ", path=" + filePath);
                }
            } catch (Exception ex) {
                Log.w(LOG_TAG, "GPS_DIAG metadata read failed for photoId=" + photoId
                        + ", path=" + filePath, ex);
            }
        }

        return new double[]{lat, lon};
    }

    @Override
    public Cursor createCursorForQuery(StringBuilder out_debugMessage, String dbgContext,
                                       QueryParameter parameters, VISIBILITY visibility,
                                       CancellationSignal cancellationSignal) {
        if (visibility != null) FotoSql.setWhereVisibility(parameters, visibility);
        return createCursorForQuery(out_debugMessage, dbgContext,
                parameters.toWhere(), parameters.toAndroidParameters(),
                parameters.toGroupBy(), parameters.toHaving(),
                parameters.toOrderBy(),
                cancellationSignal, parameters.toColumns()
        );
    }

    @Override
    public Cursor createCursorForQuery(StringBuilder out_debugMessage, String dbgContext, String from,
                                       String sqlWhereStatement, String[] sqlWhereParameters,
                                       String sqlSortOrder, CancellationSignal cancellationSignal,
                                       String... sqlSelectColums) {
        return createCursorForQuery(out_debugMessage, dbgContext,
                sqlWhereStatement, sqlWhereParameters,
                null, null,
                sqlSortOrder, cancellationSignal, sqlSelectColums);
    }
    
	
    /**
     * every cursor query should go through this. adds logging if enabled
     */
    private Cursor createCursorForQuery(StringBuilder out_debugMessage, String dbgContext,
                                        String sqlWhereStatement, String[] selectionArgs, String groupBy,
                                        String having, String sqlSortOrder,
                                        CancellationSignal cancellationSignal, final String... sqlSelectColums) {
        Cursor query = null;

        Exception excpetion = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                query = db.query(false, Impl.table, sqlSelectColums, sqlWhereStatement, selectionArgs,
                        groupBy, having, sqlSortOrder, null, cancellationSignal);
            } else {
                query = db.query(false, Impl.table, sqlSelectColums, sqlWhereStatement, selectionArgs,
                        groupBy, having, sqlSortOrder, null);
            }
        } catch (Exception ex) {
            excpetion = ex;
        } finally {
            if ((excpetion != null) || Global.debugEnabledSql || (out_debugMessage != null)) {
                final int count = (query == null) ? 0 : query.getCount();
                StringBuilder message = StringUtils.appendMessage(out_debugMessage, excpetion,
                        dbgContext, MODUL_NAME +
                                ".createCursorForQuery:\n",
                        QueryParameter.toString(sqlSelectColums, null, Impl.table, sqlWhereStatement,
                                selectionArgs, sqlSortOrder, count));
                if (out_debugMessage == null) {
                    Log.i(LOG_TAG, message.toString(), excpetion);
                }
            }
        }

        return query;
    }

    @Override
    public int execUpdate(String dbgContext, long id, ContentValues values) {
        return exexUpdateImpl(dbgContext, values, FotoSql.FILTER_COL_PK, new String[]{Long.toString(id)});
    }

    @Override
    public int execUpdate(String dbgContext, String path, ContentValues values, VISIBILITY visibility) {
        return exexUpdateImpl(dbgContext, values, FotoSql.getFilterExprPathLikeWithVisibility(visibility), new String[]{path});
    }

    @Override
    public Long insertOrUpdateMediaDatabase(String dbgContext, String dbUpdateFilterJpgFullPathName,
                                            ContentValues values, VISIBILITY visibility, Long updateSuccessValue) {
        Long result = updateSuccessValue;

        int modifyCount = execUpdate(dbgContext, dbUpdateFilterJpgFullPathName,
                values, visibility);

        if (modifyCount == 0) {
            FotoSql.addDateAdded(values);

            Uri uriWithId = execInsert(dbgContext, values);
            result = FotoSql.getId(uriWithId);
        }
        return result;
    }

    @Override
    public int exexUpdateImpl(String dbgContext, ContentValues values, String sqlWhere, String[] selectionArgs) {
        int result = -1;
        Exception excpetion = null;
        try {
            result = db.update(Impl.table, values, sqlWhere, selectionArgs);
            if (result != 0) {
                currentUpdateId++;
                currentUpdateReason = dbgContext;
            }
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

    @Override
    public ContentValues getDbContent(long id) {
        Cursor c = null;
        try {
            c = this.createCursorForQuery(null, "getDbContent",
                    Impl.table, FotoSql.FILTER_COL_PK, new String[]{"" + id}, null, null, "*");
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

    @Override
    public Uri execInsert(String dbgContext, ContentValues values) {
        long result = 0;
        Exception excpetion = null;
        try {
            result = db.insert(Impl.table, null, values);
            if (result > 0) {
                currentUpdateId++;
                currentUpdateReason = dbgContext;
            }
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
        return Uri.parse("content://apm/photo/" + result);
    }

    @Override
    public int deleteMedia(String dbgContext, String where, String[] selectionArgs, boolean preventDeleteImageFile) {
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
                        values, lastUsedWhereClause, lastSelectionArgs);

                lastUsedWhereClause = FotoSql.SQL_COL_PATH + " is null";
                lastSelectionArgs = null;
                delCount = db.delete(Impl.table, lastUsedWhereClause, lastSelectionArgs);
            } else {
                delCount = db.delete(Impl.table, lastUsedWhereClause, lastSelectionArgs);
            }
            if (delCount > 0) {
                currentUpdateId++;
                currentUpdateReason = dbgContext;
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

    @Override
    public long getCurrentUpdateId() {
        return currentUpdateId;
    }

    @Override
    public boolean mustRequery(long updateId) {
        final boolean modified = currentUpdateId != updateId;
        if (modified && MediaDBRepository.debugEnabledSqlRefresh) {
            Log.i(MediaDBRepository.LOG_TAG, "mustRequery: true because of " + currentUpdateReason);
        }
        return modified;
    }

    @Override
    public void beginTransaction() {
        db.beginTransaction();
    }

    @Override
    public void setTransactionSuccessful() {
        db.setTransactionSuccessful();
    }

    @Override
    public void endTransaction() {
        db.endTransaction();
    }

    public static class Impl {
        public static final String[] DDL = new String[]{
                "DROP TABLE IF EXISTS \"files\"",
                "CREATE TABLE \"files\" (\n" +
                        "\t_id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                        "\t_size INTEGER,\n" +
                        "\tdate_added INTEGER,\n" +
                        "\tdate_modified INTEGER,\n" +
                        "\tdatetaken INTEGER,\n" +
                        "\torientation INTEGER,\n" +
                        "\tduration INTEGER,\n" +
                        "\tbookmark INTEGER,\n" +
                        "\tmedia_type INTEGER,\n" +
                        "\twidth INTEGER,\n" +
                        "\theight INTEGER,\n" +
                        "\t_data TEXT UNIQUE COLLATE NOCASE,\n" +
                        "\ttitle TEXT,\n" +
                        "\tdescription TEXT,\n" +
                        "\t_display_name TEXT,\n" +
                        "\tmime_type TEXT,\n" +
                        "\ttags TEXT,\n" +
                        "\tlatitude DOUBLE,\n" +
                        "\tlongitude DOUBLE\n" +
                        "\t )",
                "CREATE INDEX media_type_index ON files(media_type)",
                "CREATE INDEX path_index ON files(_data)",
                "CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)",
                "CREATE INDEX title_idx ON files(title)",
        };

        public static final String table = "files";

        // 1. Local SQLite database table columns (keeps latitude and longitude for local storage schema)
        private static final String[] USED_MEDIA_COLUMNS = new String[]{
                SQL_COL_PK,
                SQL_COL_DATE_ADDED,
                SQL_COL_LAST_MODIFIED,
                SQL_COL_SIZE,
                SQL_COL_DATE_TAKEN,
                SQL_COL_ORIENTATION,
                SQL_COL_EXT_XMP_LAST_MODIFIED_DATE,
                SQL_COL_EXT_RATING,          // bookmark
                SQL_COL_EXT_MEDIA_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                SQL_COL_PATH,
                SQL_COL_EXT_TITLE,
                SQL_COL_EXT_DESCRIPTION,     // description
                SQL_COL__IMPL_DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                SQL_COL_EXT_TAGS,            // tags
                SQL_COL_LAT,                 // latitude (stored locally)
                SQL_COL_LON,                 // longitude (stored locally)
        };

        // 2. System MediaStore safe columns (OMITS unsupported columns: bookmark, description, tags, latitude, longitude)
        private static final String[] SYSTEM_MEDIA_COLUMNS = new String[]{
                SQL_COL_PK,
                SQL_COL_DATE_ADDED,
                SQL_COL_LAST_MODIFIED,
                SQL_COL_SIZE,
                SQL_COL_DATE_TAKEN,
                SQL_COL_ORIENTATION,
                SQL_COL_EXT_XMP_LAST_MODIFIED_DATE,
                // Omitted: bookmark
                SQL_COL_EXT_MEDIA_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                SQL_COL_PATH,
                SQL_COL_EXT_TITLE,
                // Omitted: description
                SQL_COL__IMPL_DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                // Omitted: tags
                // Omitted: latitude
                // Omitted: longitude
        };

        private static final int colDATE_ADDED = 1;
        private static final String FILTER_EXPR_AFFECTED_FILES
                = "(" + FotoSql.FILTER_EXPR_PRIVATE_PUBLIC
                + " OR " + SQL_COL_PATH + " like '%" + AlbumFile.SUFFIX_VALBUM + "' "
                + " OR " + SQL_COL_PATH + " like '%" + AlbumFile.SUFFIX_QUERY + "' "
                + ")";

        // MUST use SYSTEM_MEDIA_COLUMNS here so the system provider query never requests dropped columns
        private static final QueryParameter queryGetAllColumns = new QueryParameter()
                .setID(QUERY_TYPE_UNDEFINED)
                .addColumn(SYSTEM_MEDIA_COLUMNS)
                .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
                .addWhere(FILTER_EXPR_AFFECTED_FILES);

        private static String getSqlInsertWithParams() {
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(table).append("(").append(USED_MEDIA_COLUMNS[0]);
            for (int i = 1; i < USED_MEDIA_COLUMNS.length; i++) {
                sql.append(", ").append(USED_MEDIA_COLUMNS[i]);
            }
            sql.append(") VALUES (?");
            for (int i = 1; i < USED_MEDIA_COLUMNS.length; i++) {
                sql.append(", ?");
            }
            sql.append(")");
            return sql.toString();
        }

        private static String getSqlUpdateWithParams() {
            StringBuilder sql = new StringBuilder();
            sql.append("UPDATE ").append(table).append(" SET ");
            for (int i = 1; i < USED_MEDIA_COLUMNS.length; i++) {
                if (i > 1) sql.append(", ");
                sql.append(USED_MEDIA_COLUMNS[i]).append("=?");
            }
            sql.append(" WHERE ").append(USED_MEDIA_COLUMNS[0]).append("=?");
            return sql.toString();
        }

        private static int bindAndExecUpdate(Cursor c, SQLiteStatement sql) {
            sql.clearBindings();

            for (int i = 0; i < USED_MEDIA_COLUMNS.length; i++) {
                String colName = USED_MEDIA_COLUMNS[i];
                int cursorIndex = c.getColumnIndex(colName);
                int bindIndex = i + 1;

                if (cursorIndex != -1 && !c.isNull(cursorIndex)) {
                    int type = c.getType(cursorIndex);
                    if (type == Cursor.FIELD_TYPE_FLOAT) {
                        sql.bindDouble(bindIndex, c.getDouble(cursorIndex));
                    } else if (type == Cursor.FIELD_TYPE_STRING) {
                        sql.bindString(bindIndex, c.getString(cursorIndex));
                    } else {
                        sql.bindLong(bindIndex, c.getLong(cursorIndex));
                    }
                } else {
                    sql.bindNull(bindIndex);
                }
            }

            int idIndex = c.getColumnIndex(SQL_COL_PK);
            if (idIndex != -1) {
                sql.bindLong(USED_MEDIA_COLUMNS.length + 1, c.getLong(idIndex));
            }

            return sql.executeUpdateDelete();
        }

        private static void bindAndExecInsert(Cursor c, SQLiteStatement sql) {
            sql.clearBindings();

            for (int i = 0; i < USED_MEDIA_COLUMNS.length; i++) {
                String colName = USED_MEDIA_COLUMNS[i];
                int cursorIndex = c.getColumnIndex(colName);
                int bindIndex = i + 1;

                if (cursorIndex != -1 && !c.isNull(cursorIndex)) {
                    int type = c.getType(cursorIndex);
                    if (type == Cursor.FIELD_TYPE_FLOAT) {
                        sql.bindDouble(bindIndex, c.getDouble(cursorIndex));
                    } else if (type == Cursor.FIELD_TYPE_STRING) {
                        sql.bindString(bindIndex, c.getString(cursorIndex));
                    } else {
                        sql.bindLong(bindIndex, c.getLong(cursorIndex));
                    }
                } else {
                    sql.bindNull(bindIndex);
                }
            }
            sql.executeInsert();
        }

        public static int updateMedaiCopy(Context context, SQLiteDatabase db, Date lastUpdate, IProgessListener progessListener) {
            int progress = 0;
            java.util.Date startTime = new java.util.Date();

            QueryParameter query = queryGetAllColumns;
            long _lastUpdate = (lastUpdate != null) ? (lastUpdate.getTime() / 1000L) : 0L;

            if (_lastUpdate != 0) {
                query = new QueryParameter().getFrom(queryGetAllColumns);
                FotoSql.addWhereDateModifiedMinMax(query, _lastUpdate, 0);
            }
            Cursor c = null;
            SQLiteStatement sqlInsert = null;
            SQLiteStatement sqlUpdate = null;
            SQLiteStatement lastSql = null;
            boolean isUpdate = false;
            int itemCount = 0;
            int insertCout = 0;
            int updateCount = 0;

            try {
                db.beginTransaction();

                if (progessListener != null) progessListener.onProgress(progress, 0,
                        context.getString(R.string.load_db_menu_title));

                c = MediaContentproviderRepositoryImpl.createCursorForQuery(null, "updateMedaiCopy-source", context,
                        query, null, null);

                if (c == null) {
                    Log.e(LOG_TAG, "MediaDBRepository.updateMedaiCopy: Cursor returned null from provider.");
                    return 0;
                }

                itemCount = c.getCount();

                sqlInsert = db.compileStatement(getSqlInsertWithParams());
                sqlUpdate = db.compileStatement(getSqlUpdateWithParams());

                while (c.moveToNext()) {
                    isUpdate = (c.getLong(colDATE_ADDED) <= _lastUpdate);

                    if (isUpdate) {
                        updateCount++;
                        lastSql = sqlUpdate;
                        isUpdate = bindAndExecUpdate(c, sqlUpdate) > 0;
                    }

                    if (!isUpdate) {
                        insertCout++;
                        lastSql = sqlInsert;
                        bindAndExecInsert(c, sqlInsert);
                    }

                    lastSql = null;
                    if ((progessListener != null) && (progress % 100) == 0) {
                        if (!progessListener.onProgress(progress, itemCount, context.getString(R.string.scanner_update_result_format, progress))) {
                            return -progress;
                        }
                    }
                    progress++;
                }
                db.setTransactionSuccessful();
            } catch (Exception ex) {
                java.util.Date endTime = new java.util.Date();
                final String message = "MediaDBRepository.updateMedaiCopy(inserted:" + insertCout +
                        ", updated:" + updateCount +
                        ", toal:" + progress +
                        " / " + itemCount +
                        ") in " + ((endTime.getTime() - startTime.getTime()) / 1000) +
                        " Secs";
                Log.e(LOG_TAG, "Cannot insert/update: " + lastSql + " from " + c + " in " + message, ex);
            } finally {
                if (sqlInsert != null) {
                    try { sqlInsert.close(); } catch (Exception ignored) {}
                }
                if (sqlUpdate != null) {
                    try { sqlUpdate.close(); } catch (Exception ignored) {}
                }

                db.endTransaction();

                if (c != null) {
                    try { c.close(); } catch (Exception ignored) {}
                }
            }
            return progress;
        }
    }
}