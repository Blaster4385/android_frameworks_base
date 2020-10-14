/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.settingslib.wifi;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.R;
import com.android.settingslib.Utils;
import com.android.wifitrackerlib.WifiEntry;

/**
 * Preference to display a WifiEntry in a wifi picker.
 */
public class WifiEntryPreference extends Preference implements WifiEntry.WifiEntryCallback,
        View.OnClickListener {

    private static final int[] STATE_SECURED = {
            R.attr.state_encrypted
    };

    private static final int[] FRICTION_ATTRS = {
            R.attr.wifi_friction
    };

    // These values must be kept within [WifiEntry.WIFI_LEVEL_MIN, WifiEntry.WIFI_LEVEL_MAX]
    private static final int[] WIFI_CONNECTION_STRENGTH = {
            R.string.accessibility_no_wifi,
            R.string.accessibility_wifi_one_bar,
            R.string.accessibility_wifi_two_bars,
            R.string.accessibility_wifi_three_bars,
            R.string.accessibility_wifi_signal_full
    };

    // StateListDrawable to display secured lock / metered "$" icon
    @Nullable private final StateListDrawable mFrictionSld;
    private final IconInjector mIconInjector;
    private WifiEntry mWifiEntry;
    private int mLevel = -1;
    private int mWifiStandard;
    private boolean mVhtMax8SpatialStreamsSupport;
    private boolean mHe8ssCapableAp;
    private boolean mShowX; // Shows the Wi-Fi signl icon of Pie+x when it's true.
    private CharSequence mContentDescription;
    private OnButtonClickListener mOnButtonClickListener;

    public WifiEntryPreference(@NonNull Context context, @NonNull WifiEntry wifiEntry) {
        this(context, wifiEntry, new IconInjector(context));
    }

    @VisibleForTesting
    WifiEntryPreference(@NonNull Context context, @NonNull WifiEntry wifiEntry,
            @NonNull IconInjector iconInjector) {
        super(context);

        setLayoutResource(R.layout.preference_access_point);
        setWidgetLayoutResource(R.layout.access_point_friction_widget);
        mFrictionSld = getFrictionStateListDrawable();
        mWifiEntry = wifiEntry;
        mWifiEntry.setListener(this);
        mIconInjector = iconInjector;
        refresh();
    }

    public WifiEntry getWifiEntry() {
        return mWifiEntry;
    }

    @Override
    public void onBindViewHolder(final PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        final Drawable drawable = getIcon();
        if (drawable != null) {
            drawable.setLevel(mLevel);
        }

        view.itemView.setContentDescription(mContentDescription);

        // Turn off divider
        view.findViewById(R.id.two_target_divider).setVisibility(View.INVISIBLE);

        // Enable the icon button when the help string in this WifiEntry is not null.
        final ImageButton imageButton = (ImageButton) view.findViewById(R.id.icon_button);
        final ImageView frictionImageView = (ImageView) view.findViewById(
                R.id.friction_icon);
        if (mWifiEntry.getHelpUriString() != null
                && mWifiEntry.getConnectedState() == WifiEntry.CONNECTED_STATE_DISCONNECTED) {
            final Drawable drawablehelp = getDrawable(R.drawable.ic_help);
            drawablehelp.setTintList(
                    Utils.getColorAttr(getContext(), android.R.attr.colorControlNormal));
            ((ImageView) imageButton).setImageDrawable(drawablehelp);
            imageButton.setVisibility(View.VISIBLE);
            imageButton.setOnClickListener(this);
            imageButton.setContentDescription(
                    getContext().getText(R.string.help_label));

            if (frictionImageView != null) {
                frictionImageView.setVisibility(View.GONE);
            }
        } else {
            imageButton.setVisibility(View.GONE);

            if (frictionImageView != null) {
                frictionImageView.setVisibility(View.VISIBLE);
                bindFrictionImage(frictionImageView);
            }
        }
    }

    /**
     * Updates the title and summary; may indirectly call notifyChanged().
     */
    public void refresh() {
        setTitle(mWifiEntry.getTitle());
        final int level = mWifiEntry.getLevel();
        final int standard = mWifiEntry.getWifiStandard();
        final boolean vhtMax8SpatialStreamsSupport = mWifiEntry.isVhtMax8SpatialStreamsSupported();
        final boolean he8ssCapableAp = mWifiEntry.isHe8ssCapableAp();
        final boolean showX = mWifiEntry.shouldShowXLevelIcon();

        if (level != mLevel || showX != mShowX || standard != mWifiStandard ||
                he8ssCapableAp != mHe8ssCapableAp ||
                vhtMax8SpatialStreamsSupport != mVhtMax8SpatialStreamsSupport) {
            mLevel = level;
            mWifiStandard = standard;
            mHe8ssCapableAp = he8ssCapableAp;
            mVhtMax8SpatialStreamsSupport = vhtMax8SpatialStreamsSupport;
            mShowX = showX;
            updateIcon(mShowX, mLevel, mWifiStandard, mHe8ssCapableAp && mVhtMax8SpatialStreamsSupport);
            notifyChanged();
        }

        String summary = mWifiEntry.getSummary(false /* concise */);

        if (mWifiEntry.isPskSaeTransitionMode()) {
           summary = "WPA3(SAE Transition Mode) " + summary;
        } else if (mWifiEntry.isOweTransitionMode()) {
           summary = "WPA3(OWE Transition Mode) " + summary;
        } else if (mWifiEntry.getSecurity() == WifiEntry.SECURITY_SAE) {
           summary = "WPA3(SAE) " + summary;
        } else if (mWifiEntry.getSecurity() == WifiEntry.SECURITY_OWE) {
           summary = "WPA3(OWE) " + summary;
        }

        setSummary(summary);

        mContentDescription = buildContentDescription();
    }

    /**
     * Indicates the state of the WifiEntry has changed and clients may retrieve updates through
     * the WifiEntry getter methods.
     */
    public void onUpdated() {
        // TODO(b/70983952): Fill this method in
        refresh();
    }

    /**
     * Result of the connect request indicated by the WifiEntry.CONNECT_STATUS constants.
     */
    public void onConnectResult(int status) {
        // TODO(b/70983952): Fill this method in
    }

    /**
     * Result of the disconnect request indicated by the WifiEntry.DISCONNECT_STATUS constants.
     */
    public void onDisconnectResult(int status) {
        // TODO(b/70983952): Fill this method in
    }

    /**
     * Result of the forget request indicated by the WifiEntry.FORGET_STATUS constants.
     */
    public void onForgetResult(int status) {
        // TODO(b/70983952): Fill this method in
    }

    /**
     * Result of the sign-in request indecated by the WifiEntry.SIGNIN_STATUS constants
     */
    public void onSignInResult(int status) {
        // TODO(b/70983952): Fill this method in
    }


    private void updateIcon(boolean showX, int level, int standard, boolean isReady) {
        if (level == -1) {
            setIcon(null);
            return;
        }

        final Drawable drawable = mIconInjector.getIcon(showX, level, standard, isReady);
        if (drawable != null) {
            drawable.setTintList(Utils.getColorAttr(getContext(),
                    android.R.attr.colorControlNormal));
            setIcon(drawable);
        } else {
            setIcon(null);
        }
    }

    @Nullable
    private StateListDrawable getFrictionStateListDrawable() {
        TypedArray frictionSld;
        try {
            frictionSld = getContext().getTheme().obtainStyledAttributes(FRICTION_ATTRS);
        } catch (Resources.NotFoundException e) {
            // Fallback for platforms that do not need friction icon resources.
            frictionSld = null;
        }
        return frictionSld != null ? (StateListDrawable) frictionSld.getDrawable(0) : null;
    }

    /**
     * Binds the friction icon drawable using a StateListDrawable.
     *
     * <p>Friction icons will be rebound when notifyChange() is called, and therefore
     * do not need to be managed in refresh()</p>.
     */
    private void bindFrictionImage(ImageView frictionImageView) {
        if (frictionImageView == null || mFrictionSld == null) {
            return;
        }
        if ((mWifiEntry.getSecurity() != WifiEntry.SECURITY_NONE)
                && (mWifiEntry.getSecurity() != WifiEntry.SECURITY_OWE)) {
            mFrictionSld.setState(STATE_SECURED);
        }
        frictionImageView.setImageDrawable(mFrictionSld.getCurrent());
    }

    /**
     * Helper method to generate content description string.
     */
    @VisibleForTesting
    CharSequence buildContentDescription() {
        final Context context = getContext();

        CharSequence contentDescription = getTitle();
        final CharSequence summary = getSummary();
        if (!TextUtils.isEmpty(summary)) {
            contentDescription = TextUtils.concat(contentDescription, ",", summary);
        }
        int level = mWifiEntry.getLevel();
        if (level >= 0 && level < WIFI_CONNECTION_STRENGTH.length) {
            contentDescription = TextUtils.concat(contentDescription, ",",
                    context.getString(WIFI_CONNECTION_STRENGTH[level]));
        }
        return TextUtils.concat(contentDescription, ",",
                mWifiEntry.getSecurity() == WifiEntry.SECURITY_NONE
                        ? context.getString(R.string.accessibility_wifi_security_type_none)
                        : context.getString(R.string.accessibility_wifi_security_type_secured));
    }


    static class IconInjector {
        private final Context mContext;

        IconInjector(Context context) {
            mContext = context;
        }

        public Drawable getIcon(boolean showX, int level, int standard, boolean isReady) {
            return mContext.getDrawable(Utils.getWifiIconResource(showX, level, standard, isReady));
        }
    }

    /**
     * Set listeners, who want to listen the button client event.
     */
    public void setOnButtonClickListener(OnButtonClickListener listener) {
        mOnButtonClickListener = listener;
        notifyChanged();
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.icon_button) {
            if (mOnButtonClickListener != null) {
                mOnButtonClickListener.onButtonClick(this);
            }
        }
    }

    /**
     * Callback to inform the caller that the icon button is clicked.
     */
    public interface OnButtonClickListener {

        /**
         * Register to listen the button click event.
         */
        void onButtonClick(WifiEntryPreference preference);
    }

    private Drawable getDrawable(@DrawableRes int iconResId) {
        Drawable buttonIcon = null;

        try {
            buttonIcon = getContext().getDrawable(iconResId);
        } catch (Resources.NotFoundException exception) {
            // Do nothing
        }
        return buttonIcon;
    }

}
