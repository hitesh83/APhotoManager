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

package de.k3b.android.androFotoFinder.imagedetail;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter; // <-- Added missing PagerAdapter import
import androidx.viewpager.widget.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.display.SimpleBitmapDisplayer;

import java.io.File;
import java.util.Date;

import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.util.DBUtils;
import de.k3b.android.util.GarbageCollector;
import de.k3b.android.util.MenuUtils;
import de.k3b.android.util.PhotoChangeNotifyer;
import de.k3b.android.util.ResourceUtils;
import de.k3b.media.PhotoPropertiesBulkUpdateService;

/**
 * Adapter for androidx.viewpager.widget.ViewPager that allows swiping next/previous image.
 * <br>
 * Translates between position in ViewPager and content page content with image
 * <p>
 * Created by k3b on 04.07.2015.
 */
public class ImagePagerAdapterFromCursor extends PagerAdapter implements PhotoChangeNotifyer.PhotoChangedListener {
    private static final int MAX_IMAGE_DIMENSION = HugeImageLoader.getMaxTextureSize();

    /** column alias for optional sql expression to show ContextDetails */
    public static final String CONTEXT_COLUMN_FIELD = "ContextDetails";

    // debug support
    private static int id = 0;
    protected final String mDebugPrefix;

    private final Activity mActivity;
    // workaround because setEllipsize(TextUtils.TruncateAt.MIDDLE) is not possible for title
    private final int mMaxTitleLength;

    private Cursor mCursor = null; // the content of the page

    protected DisplayImageOptions mDisplayImageOptions;
    private Menu mMenu = null;

    private ImageButtonControllerImpl mImageButtonController = null;

    public ImagePagerAdapterFromCursor(final Activity context, String name) {
        mActivity = context;
        mDebugPrefix = "ImagePagerAdapterFromCursor#" + (id++) + "@" + name + " ";
        Global.debugMemory(mDebugPrefix, "ctor");
        mMaxTitleLength = context.getResources().getInteger(R.integer.title_length_in_chars);

        mImageButtonController = new ImageButtonControllerImpl();
        mDisplayImageOptions = new DisplayImageOptions.Builder()
                .showImageOnLoading(R.drawable.image_loading)
                .showImageForEmptyUri(R.drawable.image_loading)
                .showImageOnFail(R.drawable.image_loading)
                .cacheInMemory(false)
                .cacheOnDisk(true)
                .considerExifParams(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .displayer(new SimpleBitmapDisplayer())
                .build();
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + "()");
        }
    }

    public void setMenu(Menu menu) {
        mMenu = menu;
    }

    /**
     * Swap in a new Cursor, returning the old Cursor. Unlike the returned old Cursor,
     * it is *not* closed.
     *
     * @param newCursor The new cursor to be used.
     * @return Returns the previously set Cursor, or null if there was not one.
     */
    public Cursor swapCursor(Cursor newCursor) {
        if (newCursor == mCursor) {
            return null;
        }
        Cursor oldCursor = mCursor;
        mCursor = newCursor;
        return oldCursor;
    }

    @Override
    public int getCount() {
        int result = 0;
        if (this.mCursor != null) {
            result = this.mCursor.getCount();
        }
        return result;
    }

    public CharSequence getPageTitle(int position) {
        Cursor cursor = getCursorAt(position);
        if (cursor != null) {
            String name = DBUtils.getString(cursor, FotoSql.SQL_COL_DISPLAY_TEXT, null);
            if (name != null) {
                StringBuilder result = new StringBuilder();

                if (Global.debugEnabled) {
                    long imageID = DBUtils.getLong(cursor, FotoSql.SQL_COL_PK, 0);
                    result.append("#").append(imageID).append(":");
                }

                // workaround because setEllipsize(TextUtils.TruncateAt.MIDDLE) is not possible for title
                if (name.length() > mMaxTitleLength) {
                    result.append("..").append(name.substring(name.length() - (mMaxTitleLength - 2)));
                } else {
                    result.append(name);
                }

                return result.toString();
            }
        }
        return mActivity.getString(R.string.image_loading_at_position_format, position);
    }

    public String getFullFilePath(int position) {
        Cursor cursor = getCursorAt(position);
        return DBUtils.getString(cursor, FotoSql.SQL_COL_DISPLAY_TEXT, null);
    }

    public long getImageId(int position) {
        Cursor cursor = getCursorAt(position);
        return DBUtils.getLong(cursor, FotoSql.SQL_COL_PK, 0);
    }

    public Date getDatePhotoTaken(int position) {
        Cursor cursor = getCursorAt(position);
        return FotoSql.getDate(cursor, cursor.getColumnIndex(FotoSql.SQL_COL_DATE_TAKEN));
    }

    public boolean hasGeo(int position) {
        Cursor cursor = getCursorAt(position);
        return !DBUtils.isNull(cursor, FotoSql.SQL_COL_GPS, true);
    }

    @Override
    public View instantiateItem(ViewGroup container, int position) {
        Cursor cursor = getCursorAt(position);
        if (cursor != null) {
            String fullPhotoPath = getFullFilePath(position);

            int colSize = cursor.getColumnIndex(FotoSql.SQL_COL_WIDTH);
            if (colSize < 0) {
                colSize = cursor.getColumnIndex(FotoSql.SQL_COL_SIZE);
            }

            int size = (colSize >= 0) ? cursor.getInt(colSize) : 32767;

            if (fullPhotoPath != null) {
                return createViewWithContent(position, container, fullPhotoPath, "instantiateItemFromCursor(#", size);
            }
        }
        return null;
    }

    private Cursor getCursorAt(int position) {
        if ((this.mCursor != null) && (position >= 0) && (position < this.mCursor.getCount())) {
            this.mCursor.moveToPosition(position);
            return this.mCursor;
        }
        return null;
    }

    public int getPositionFromPath(String path) {
        int result = -1;
        if ((this.mCursor != null) && (path != null)) {
            int index = mCursor.getColumnIndex(FotoSql.SQL_COL_DISPLAY_TEXT);
            if ((index >= 0) && (mCursor.moveToFirst())) {
                do {
                    if (path.equals(mCursor.getString(index))) {
                        result = mCursor.getPosition();
                        break;
                    }
                } while (mCursor.moveToNext());
            }
        }
        if (Global.debugEnabledViewItem) Log.i(Global.LOG_CONTEXT, mDebugPrefix + "getPositionFromPath(" + path + ") => " + result);
        return result;
    }

    @NonNull
    protected View createViewWithContent(int position, ViewGroup container, String fullPhotoPath, String debugContext, int size) {
        final Context context = container.getContext();
        final boolean useLayout = true;

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        PhotoViewEx photoView;
        View root;
        TextView contextTextView = null;

        if (useLayout) {
            root = inflater.inflate(R.layout.pager_item_image, container, false);
            photoView = (PhotoViewEx) root.findViewById(R.id.image);
            mImageButtonController.create((ImageButton) root.findViewById(R.id.cmd_any));
            contextTextView = (TextView) root.findViewById(R.id.text);
        } else {
            photoView = new PhotoViewEx(context);
            root = photoView;
        }

        if (contextTextView != null) {
            String contextText = DBUtils.getString(getCursorAt(position), CONTEXT_COLUMN_FIELD, null);
            if ((contextText != null) && (contextText.length() > 0)) {
                contextTextView.setVisibility(View.VISIBLE);
                contextTextView.setText(ImageContextController.sqlFormat(contextText));
            } else {
                contextTextView.setVisibility(View.INVISIBLE);
            }
        }

        photoView.setMaximumScale(20);
        photoView.setMediumScale(5);

        final File imageFile = new File(fullPhotoPath);
        String loadType;

        if (size > Global.imageDetailThumbnailIfBiggerThan) {
            loadType = "image too big using thumb ";
            setImageFromThumbnail(photoView, imageFile);
        } else {
            try {
                loadType = "image small enough ";
                Bitmap bitmap = HugeImageLoader.loadImage(imageFile, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION);
                photoView.setImageBitmap(bitmap);
                photoView.setImageReloadFile(null);
                photoView.setDebugPrefix(imageFile.getName());
            } catch (OutOfMemoryError err) {
                loadType = "small image out of memory using thumb ";
                setImageFromThumbnail(photoView, imageFile);
            }
        }
        final int rotationInDegrees = PhotoPropertiesBulkUpdateService.getRotationFromExifOrientation(fullPhotoPath, null);
        if (Global.debugEnabledViewItem) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + debugContext + position + ", rotation=" +
                    rotationInDegrees + ", " + loadType + ") => " + fullPhotoPath + " => " + photoView);
        }
        photoView.setRotationTo(rotationInDegrees);

        container.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return root;
    }

    private void setImageFromThumbnail(PhotoViewEx photoView, File imageFile) {
        photoView.setImageReloadFile(imageFile);
        ImageLoader.getInstance().displayImage("file://" + imageFile, photoView, mDisplayImageOptions);
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        if (Global.debugEnabledViewItem) Log.i(Global.LOG_CONTEXT, mDebugPrefix + "destroyItem(#" + position + ") " + object);
        container.removeView((View) object);
        GarbageCollector.freeMemory((View) object);
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return (view != null) && view.equals(object);
    }

    @Override
    public void setPrimaryItem(ViewGroup container, int position, Object object) {
        super.setPrimaryItem(container, position, object);
        this.mActivity.setTitle(this.getPageTitle(position));
        if (object != null) {
            mImageButtonController.create((ImageButton) ((View) object).findViewById(R.id.cmd_any));
        }
    }

    public void setIconResourceName(String name) {
        mImageButtonController.setContext(name);
    }

    private class ImageButtonControllerImpl {
        private MenuItem mMenuItem = null;
        private View.OnClickListener mClickListener;
        private final View.OnLongClickListener mLongClickListener;

        public ImageButtonControllerImpl() {
            mClickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ((mMenuItem != null) && (mMenu != null)) {
                        mMenu.performIdentifierAction(mMenuItem.getItemId(), Menu.FLAG_ALWAYS_PERFORM_CLOSE);
                    }
                }
            };
            mLongClickListener = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if ((mActivity != null) && (mMenuItem != null)) {
                        Toast.makeText(mActivity, mMenuItem.getTitle(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            };
        }

        public void create(ImageButton btn) {
            if (btn != null) {
                Drawable icon = null;
                if ((mMenuItem != null) && (mMenuItem.getOrder() % 2 == 0)) {
                    icon = mMenuItem.getIcon();
                    if ((icon == null) && (mMenuItem.getItemId() == R.id.action_details)) {
                        icon = ResourceUtils.getDrawable(mActivity, android.R.drawable.ic_dialog_info);
                    }
                }

                if (icon != null) {
                    btn.setImageDrawable(icon);
                    btn.setVisibility(View.VISIBLE);
                } else {
                    btn.setVisibility(View.INVISIBLE);
                }
                btn.setOnClickListener(mClickListener);
                btn.setOnLongClickListener(mLongClickListener);
            }
        }

        public void setContext(MenuItem context) {
            this.mMenuItem = context;
        }

        public void setContext(String name) {
            setContext(MenuUtils.findByTitle(mMenu, name, 2));
        }

        public String getContext() {
            CharSequence icon = (mMenuItem == null) ? null : mMenuItem.getTitle();
            if (icon == null) return null;
            return icon.toString();
        }

        public int getMenuId() {
            return (mMenuItem == null) ? 0 : mMenuItem.getItemId();
        }
    }

    public int getMenuId() {
        return (mImageButtonController == null) ? 0 : mImageButtonController.getMenuId();
    }

    public void setContext(MenuItem context) {
        if (mImageButtonController != null) mImageButtonController.setContext(context);
    }

    @Override
    public void onNotifyPhotoChanged() {
        notifyDataSetChanged();
    }
}