/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.metrics.LogMaker;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Controller for QSPanel views.
 *
 * @param <T> Type of QSPanel.
 */
public abstract class QSPanelControllerBase<T extends QSPanel> extends ViewController<T>
        implements Dumpable{
    protected final QSTileHost mHost;
    private final QSCustomizerController mQsCustomizerController;
    private final MediaHost mMediaHost;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    private final DumpManager mDumpManager;
    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();

    private int mLastOrientation;
    private String mCachedSpecs = "";
    private QSTileRevealController mQsTileRevealController;
    private float mRevealExpansion;

    private final QSHost.Callback mQSHostCallback = this::setTiles;

    private final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            new QSPanel.OnConfigurationChangedListener() {
                @Override
                public void onConfigurationChange(Configuration newConfig) {
                    if (newConfig.orientation != mLastOrientation) {
                        mLastOrientation = newConfig.orientation;
                        switchTileLayout(false);
                    }
                }
            };

    protected QSPanelControllerBase(T view, QSTileHost host,
            QSCustomizerController qsCustomizerController, MediaHost mediaHost,
            MetricsLogger metricsLogger, UiEventLogger uiEventLogger, DumpManager dumpManager) {
        super(view);
        mHost = host;
        mQsCustomizerController = qsCustomizerController;
        mMediaHost = mediaHost;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mDumpManager = dumpManager;
    }

    @Override
    protected void onViewAttached() {
        mQsTileRevealController = createTileRevealController();
        if (mQsTileRevealController != null) {
            mQsTileRevealController.setExpansion(mRevealExpansion);
        }

        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mHost.addCallback(mQSHostCallback);
        mMediaHost.addVisibilityChangeListener(aBoolean -> {
            switchTileLayout(false);
            return null;
        });
        setTiles();
        switchTileLayout(true);
        mDumpManager.registerDumpable(mView.getDumpableTag(), this);
    }

    @Override
    protected void onViewDetached() {
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mHost.removeCallback(mQSHostCallback);

        for (TileRecord record : mRecords) {
            record.tile.removeCallbacks();
        }
        mRecords.clear();
        mDumpManager.unregisterDumpable(mView.getDumpableTag());
    }

    protected QSTileRevealController createTileRevealController() {
        return null;
    }

    /** */
    public void setTiles() {
        setTiles(mHost.getTiles(), false);
    }

    /** */
    public void setTiles(Collection<QSTile> tiles, boolean collapsedView) {
        // TODO(b/168904199): move this logic into QSPanelController.
        if (!collapsedView && mQsTileRevealController != null) {
            mQsTileRevealController.updateRevealedTiles(tiles);
        }

        for (QSPanelControllerBase.TileRecord record : mRecords) {
            mView.removeTile(record);
            record.tile.removeCallback(record.callback);
        }
        mRecords.clear();
        mCachedSpecs = "";
        for (QSTile tile : tiles) {
            addTile(tile, collapsedView);
        }
    }

    /** */
    public void refreshAllTiles() {
        for (QSPanelControllerBase.TileRecord r : mRecords) {
            r.tile.refreshState();
        }
    }

    private void addTile(final QSTile tile, boolean collapsedView) {
        final TileRecord r = new TileRecord();
        r.tile = tile;
        r.tileView = mHost.createTileView(tile, collapsedView);
        mView.addTile(r);
        mRecords.add(r);
        mCachedSpecs = getTilesSpecs();

    }

    /** */
    public void clickTile(ComponentName tile) {
        final String spec = CustomTile.toSpec(tile);
        for (TileRecord record : mRecords) {
            if (record.tile.getTileSpec().equals(spec)) {
                record.tile.click();
                break;
            }
        }
    }
    protected QSTile getTile(String subPanel) {
        for (int i = 0; i < mRecords.size(); i++) {
            if (subPanel.equals(mRecords.get(i).tile.getTileSpec())) {
                return mRecords.get(i).tile;
            }
        }
        return mHost.createTile(subPanel);
    }


    QSTileView getTileView(QSTile tile) {
        for (QSPanelControllerBase.TileRecord r : mRecords) {
            if (r.tile == tile) {
                return r.tileView;
            }
        }
        return null;
    }

    private String getTilesSpecs() {
        return mRecords.stream()
                .map(tileRecord ->  tileRecord.tile.getTileSpec())
                .collect(Collectors.joining(","));
    }

    /** */
    public void setExpanded(boolean expanded) {
        mView.setExpanded(expanded);
        mMetricsLogger.visibility(MetricsEvent.QS_PANEL, expanded);
        if (!expanded) {
            mUiEventLogger.log(mView.closePanelEvent());
            closeDetail();
        } else {
            mUiEventLogger.log(mView.openPanelEvent());
            logTiles();
        }
    }

    /** */
    public void closeDetail() {
        if (mQsCustomizerController.isShown()) {
            mQsCustomizerController.hide();
            return;
        }
        mView.closeDetail();
    }

    /** */
    public void openDetails(String subPanel) {
        QSTile tile = getTile(subPanel);
        // If there's no tile with that name (as defined in QSFactoryImpl or other QSFactory),
        // QSFactory will not be able to create a tile and getTile will return null
        if (tile != null) {
            mView.showDetailAdapter(
                    true, tile.getDetailAdapter(), new int[]{mView.getWidth() / 2, 0});
        }
    }


    void setListening(boolean listening) {
        mView.setListening(listening, mCachedSpecs);
    }

    boolean switchTileLayout(boolean force) {
        if (mView.switchTileLayout(force, mRecords)) {
            setTiles();
            return true;
        }
        return false;
    }

    private void logTiles() {
        for (int i = 0; i < mRecords.size(); i++) {
            QSTile tile = mRecords.get(i).tile;
            mMetricsLogger.write(tile.populate(new LogMaker(tile.getMetricsCategory())
                    .setType(MetricsEvent.TYPE_OPEN)));
        }
    }

    /** Set the expansion on the associated {@link QSTileRevealController}. */
    public void setRevealExpansion(float expansion) {
        mRevealExpansion = expansion;
        if (mQsTileRevealController != null) {
            mQsTileRevealController.setExpansion(expansion);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + ":");
        pw.println("  Tile records:");
        for (QSPanelControllerBase.TileRecord record : mRecords) {
            if (record.tile instanceof Dumpable) {
                pw.print("    "); ((Dumpable) record.tile).dump(fd, pw, args);
                pw.print("    "); pw.println(record.tileView.toString());
            }
        }
    }

    public QSPanel.QSTileLayout getTileLayout() {
        return mView.getTileLayout();
    }

    /** */
    public static final class TileRecord extends QSPanel.Record {
        public QSTile tile;
        public com.android.systemui.plugins.qs.QSTileView tileView;
        public boolean scanState;
        public QSTile.Callback callback;
    }
}
