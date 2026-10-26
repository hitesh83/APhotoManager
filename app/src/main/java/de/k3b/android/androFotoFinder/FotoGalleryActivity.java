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

package de.k3b.android.androFotoFinder;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import de.k3b.android.androFotoFinder.BuildConfig;
import de.k3b.LibGlobal;
import de.k3b.android.androFotoFinder.directory.DirectoryGui;
import de.k3b.android.androFotoFinder.gallery.cursor.GalleryCursorFragment;
import de.k3b.android.androFotoFinder.imagedetail.ImageDetailActivityViewPager;
import de.k3b.android.androFotoFinder.locationmap.GeoEditActivity;
import de.k3b.android.androFotoFinder.queries.AndroidAlbumUtils;
import de.k3b.android.androFotoFinder.queries.FotoViewerParameter;
import de.k3b.android.androFotoFinder.queries.Queryable;
import de.k3b.android.util.GarbageCollector;
import de.k3b.android.util.IntentUtil;
import de.k3b.android.widget.AboutDialogPreference;
import de.k3b.android.widget.BaseQueryActivity;
import de.k3b.database.QueryParameter;
import de.k3b.io.IDirectory;
import de.k3b.io.collections.SelectedItems;

/**
 * Gallery: Show zero or more images in a grid optionally filtered by a
 * base query plus sub query (either path or date or geo or tag or in-any)
 */
public class FotoGalleryActivity extends BaseQueryActivity implements
        OnGalleryInteractionListener {

    // multi selection support
    private SelectedItems mSelectedItems = null;

    private Queryable mGalleryGui;

    private DirectoryGui mDirGui;

    public static void showActivity(String debugContext, Activity context, QueryParameter query, int requestCode) {
        Intent intent = new Intent(context, FotoGalleryActivity.class);

        AndroidAlbumUtils.saveFilterAndQuery(context, null, intent, null, null, query);

        IntentUtil.startActivity(debugContext, context, requestCode, intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Global.debugMemory(mDebugPrefix, "onCreate");
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();

        if (BuildConfig.DEBUG) {
            LibGlobal.itpcWriteSupport = false;
        }
        if (Global.debugEnabled && (intent != null)) {
            Log.d(Global.LOG_CONTEXT, mDebugPrefix + "onCreate " + intent.toUri(Intent.URI_INTENT_SCHEME));
        }

        setContentView(R.layout.activity_gallery);

        onCreateData(savedInstanceState);

        // Using native FragmentManager to match legacy Fragment implementations
        FragmentManager fragmentManager = getFragmentManager();
        mGalleryGui = (Queryable) fragmentManager.findFragmentById(R.id.galleryCursor);

        if (mGalleryGui instanceof GalleryCursorFragment) {
            this.mSelectedItems = ((GalleryCursorFragment) mGalleryGui).getSelectedItems();
        }

        // on tablet separate dir navigator fragment
        mDirGui = (DirectoryGui) fragmentManager.findFragmentById(R.id.directoryFragment);

        if (FotoViewerParameter.galleryHasEmbeddedDirPicker) {
            if (mDirGui == null) {
                // on small screen/cellphone DirectoryGui is part of gallery
                mDirGui = (DirectoryGui) fragmentManager.findFragmentById(R.id.galleryCursor);
            } else {
                mHasEmbeddedDirPicker = true;
            }
        } else {
            if (mDirGui != null) {
                fragmentManager.beginTransaction().remove((Fragment) mDirGui).commit();
                mDirGui = null;
            }
        }

        setTitle();
        reloadGui("onCreate");
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mGalleryGui instanceof GalleryCursorFragment) {
            this.mSelectedItems = ((GalleryCursorFragment) mGalleryGui).getSelectedItems();
        }
    }

    @Override
    protected void onDestroy() {
        Global.debugMemory(mDebugPrefix, "onDestroy start");
        super.onDestroy();

        GarbageCollector.freeMemory(findViewById(R.id.root_view));
        mGalleryGui = null;
        mDirGui = null;

        System.gc();
        Global.debugMemory(mDebugPrefix, "onDestroy end");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Intent intent = this.getIntent();
        String action = (intent != null) ? intent.getAction() : null;
        if ((action == null) || (((Intent.ACTION_PICK.compareTo(action) != 0) && (Intent.ACTION_GET_CONTENT.compareTo(action) != 0)))) {
            MenuInflater inflater = getMenuInflater();

            inflater.inflate(R.menu.menu_gallery_non_selected_only, menu);
            inflater.inflate(R.menu.menu_gallery_non_multiselect, menu);

            if (Global.useAo10MediaImageDbReplacement) {
                inflater.inflate(R.menu.menu_ao10, menu);
            }

            Global.fixMenu(this, menu);
        }

        final boolean result = super.onCreateOptionsMenu(menu);
        return result;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sorter = menu.findItem(R.id.cmd_sort);

        if (sorter != null) {
            String sortTitle = getString(R.string.sort_menu_title) +
                    ": " +
                    mGalleryQueryParameter.getSortDisplayName(this);
            sorter.setTitle(sortTitle);
        }
        AboutDialogPreference.onPrepareOptionsMenu(this, menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        // Converted switch to if-else to bypass non-constant resource ID restrictions in modern Gradle builds
        if (id == R.id.cmd_settings) {
            SettingsActivity.showActivity(this);
            return true;
        } else if (id == R.id.cmd_about) {
            AboutDialogPreference.createAboutDialog(this).show();
            return true;
        } else if (id == R.id.cmd_db_reload) {
            AndroFotoFinderApp.getMediaContent2DbUpdateService().rebuild(this, null);
            return true;
        } else if (id == R.id.cmd_more) {
            new Handler().postDelayed(new Runnable() {
                public void run() {
                    openOptionsMenu();
                }
            }, 200);
            return true;
        } else {
            return onOptionsItemSelected(item, this.mSelectedItems);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode,
                                    final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (mGalleryGui instanceof Fragment) {
            ((Fragment) mGalleryGui).onActivityResult(requestCode, resultCode, intent);
        }

        switch (requestCode) {
            case ImageDetailActivityViewPager.ACTIVITY_ID:
                if (resultCode == ImageDetailActivityViewPager.RESULT_CHANGE) {
                    invalidateDirectories(mDebugPrefix + "#onActivityResult from ImageDetailActivityViewPager");
                }
                break;
            case GeoEditActivity.RESULT_ID:
                if (resultCode == ImageDetailActivityViewPager.RESULT_CHANGE) {
                    invalidateDirectories(mDebugPrefix + "#onActivityResult from GeoEditActivity");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onGalleryImageClick(long imageId, Uri imageUri, int position) {
        Global.debugMemory(mDebugPrefix, "onGalleryImageClick");
        QueryParameter imageDetailQuery = this.mGalleryQueryParameter.calculateEffectiveGalleryContentQuery();
        ImageDetailActivityViewPager.showActivity("[1]#" + imageId, this, imageUri, position, imageDetailQuery, ImageDetailActivityViewPager.ACTIVITY_ID);
    }

    @Override
    protected void defineDirectoryNavigation(IDirectory directoryRoot) {
        if (mDirGui != null) {
            mDirGui.defineDirectoryNavigation(directoryRoot, mGalleryQueryParameter.getDirQueryID(),
                    mGalleryQueryParameter.getCurrentSubFilterSettings().getPath());
        }
    }

    @Override
    protected void reloadGui(String why) {
        if (mGalleryGui != null) {
            QueryParameter query = this.mGalleryQueryParameter.calculateEffectiveGalleryContentQuery();
            if (query != null) {
                this.mGalleryGui.requery(this, query, mDebugPrefix + why);
            }
        }

        if (mDirGui != null) {
            String currentPath = this.mGalleryQueryParameter.getCurrentSubFilterSettings().getPath();
            if (currentPath != null) {
                mDirGui.navigateTo(currentPath);
            }
        }
    }

    @Override
    public String toString() {
        return mDebugPrefix + "->" + this.mGalleryGui;
    }
}