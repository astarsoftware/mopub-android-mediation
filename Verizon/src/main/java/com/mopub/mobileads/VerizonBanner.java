package com.mopub.mobileads;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;

import com.astarsoftware.dependencies.DependencyInjector;
import com.astarsoftware.notification.NotificationCenter;
import com.mopub.common.MoPub;
import com.mopub.common.Preconditions;
import com.mopub.common.logging.MoPubLog;
import com.verizon.ads.Bid;
import com.verizon.ads.BidRequestListener;
import com.verizon.ads.CreativeInfo;
import com.verizon.ads.ErrorInfo;
import com.verizon.ads.RequestMetadata;
import com.verizon.ads.VASAds;
import com.verizon.ads.edition.StandardEdition;
import com.verizon.ads.inlineplacement.AdSize;
import com.verizon.ads.inlineplacement.InlineAdFactory;
import com.verizon.ads.inlineplacement.InlineAdView;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CLICKED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CUSTOM;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_FAILED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_SUCCESS;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.SHOW_FAILED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.WILL_LEAVE_APPLICATION;
import static com.mopub.mobileads.MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR;
import static com.mopub.mobileads.MoPubErrorCode.INTERNAL_ERROR;
import static com.mopub.mobileads.VerizonUtils.convertErrorInfoToMoPub;

public class VerizonBanner extends CustomEventBanner {

    private static final String ADAPTER_NAME = VerizonBanner.class.getSimpleName();

    private static final String PLACEMENT_ID_KEY = "placementId";
    private static final String SITE_ID_KEY = "siteId";
    private static final String HEIGHT_KEY = "com_mopub_ad_height";
    private static final String WIDTH_KEY = "com_mopub_ad_width";

    private InlineAdView verizonInlineAd;
    private CustomEventBannerListener bannerListener;
    private FrameLayout internalView;
	private NotificationCenter notificationCenter;


	private int adWidth, adHeight;

    @NonNull
    private VerizonAdapterConfiguration verizonAdapterConfiguration;

    public VerizonBanner() {
    	verizonAdapterConfiguration = new VerizonAdapterConfiguration();
		DependencyInjector.requestInjection(this, "NotificationCenter", "notificationCenter");
    }

	public void setNotificationCenter(NotificationCenter notificationCenter) {
		this.notificationCenter = notificationCenter;
	}

    @Override
    protected void loadBanner(final Context context,
                              final CustomEventBannerListener customEventBannerListener,
                              final Map<String, Object> localExtras,
                              final Map<String, String> serverExtras) {

        bannerListener = customEventBannerListener;

        if (serverExtras == null || serverExtras.isEmpty()) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Ad request to Verizon failed because " +
                    "serverExtras is null or empty");

            logAndNotifyBannerFailed(LOAD_FAILED, ADAPTER_CONFIGURATION_ERROR);

            return;
        }

        if (!VASAds.isInitialized()) {
            final String siteId = serverExtras.get(getSiteIdKey());

            if (TextUtils.isEmpty(siteId)) {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "Ad request to Verizon failed because " +
                        "siteId is empty");

                logAndNotifyBannerFailed(LOAD_FAILED, ADAPTER_CONFIGURATION_ERROR);

                return;
            }

            if (!(context instanceof Activity)) {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "Ad request to Verizon failed because " +
                        "context is not an Activity");

                logAndNotifyBannerFailed(LOAD_FAILED, ADAPTER_CONFIGURATION_ERROR);

                return;
            }

            final boolean success = StandardEdition.initializeWithActivity((Activity) context, siteId);

            if (!success) {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "Failed to initialize the Verizon SDK");

                logAndNotifyBannerFailed(LOAD_FAILED, ADAPTER_CONFIGURATION_ERROR);

                return;
            }
        }

        final String placementId = serverExtras.get(getPlacementIdKey());

        if (localExtras == null || localExtras.isEmpty()) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "localExtras is null. Unable to extract banner sizes");

            logAndNotifyBannerFailed(LOAD_FAILED, ADAPTER_CONFIGURATION_ERROR);

            return;
        }

        if (localExtras.get(getWidthKey()) != null) {
            adWidth = (int) localExtras.get(getWidthKey());
        }

        if (localExtras.get(getHeightKey()) != null) {
            adHeight = (int) localExtras.get(getHeightKey());
        }

        if (TextUtils.isEmpty(placementId) || adWidth <= 0 || adHeight <= 0) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME,
                    "Ad request to Verizon failed because either the placement ID, or width, or " +
                            "height is <= 0");

            logAndNotifyBannerFailed(LOAD_FAILED, INTERNAL_ERROR);

            return;
        }

        internalView = new FrameLayout(context);

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        internalView.setLayoutParams(lp);

        VASAds.setLocationEnabled(MoPub.getLocationAwareness() != MoPub.LocationAwareness.DISABLED);

        final Bid bid = BidCache.get(placementId);
        final InlineAdFactory inlineAdFactory = new InlineAdFactory(context, placementId,
                Collections.singletonList(new AdSize(adWidth, adHeight)),
                new VerizonInlineAdFactoryListener());

        if (bid == null) {
            final RequestMetadata requestMetadata = new RequestMetadata.Builder()
                    .setMediator(VerizonAdapterConfiguration.MEDIATOR_ID)
                    .build();

            inlineAdFactory.setRequestMetaData(requestMetadata);

            inlineAdFactory.load(new VerizonInlineAdListener());
        } else {
            inlineAdFactory.load(bid, new VerizonInlineAdListener());
        }

        verizonAdapterConfiguration.setCachedInitializationParameters(context, serverExtras);
    }


    /**
     * Call this method to cache a super auction bid for the specified placement ID
     *
     * @param context            a non-null Context
     * @param placementId        a valid placement ID. Cannot be null or empty.
     * @param adSizes            a list of acceptable {@link AdSize}s. Cannot be null or empty.
     * @param requestMetadata    a {@link RequestMetadata} instance for the request or null
     * @param bidRequestListener an instance of {@link BidRequestListener}. Cannot be null.
     */
    public static void requestBid(final Context context, final String placementId,
                                  final List<AdSize> adSizes,
                                  final RequestMetadata requestMetadata,
                                  final BidRequestListener bidRequestListener) {

        Preconditions.checkNotNull(context, "Super auction bid skipped because the " +
                "context is null");
        Preconditions.checkNotNull(placementId, "Super auction bid skipped because the " +
                "placement ID is null");
        Preconditions.checkNotNull(adSizes, "Super auction bid skipped because the " +
                "adSizes list is null");
        Preconditions.checkNotNull(bidRequestListener, "Super auction bid skipped " +
                "because the bidRequestListener is null");

        if (TextUtils.isEmpty(placementId)) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Super auction bid skipped because the " +
                    "placement ID is empty");

            return;
        }

        if (adSizes.isEmpty()) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Super auction bid skipped because the " +
                    "adSizes list is empty");

            return;
        }

        final RequestMetadata.Builder builder = new RequestMetadata.Builder(requestMetadata);
        final RequestMetadata actualRequestMetadata = builder
                .setMediator(VerizonAdapterConfiguration.MEDIATOR_ID)
                .build();

        InlineAdFactory.requestBid(context, placementId, adSizes, actualRequestMetadata,
                new BidRequestListener() {

                    @Override
                    public void onComplete(Bid bid, ErrorInfo errorInfo) {

                        if (errorInfo == null) {
                            BidCache.put(placementId, bid);
                        }

                        bidRequestListener.onComplete(bid, errorInfo);
                    }
                });
    }

    @Override
    protected void onInvalidate() {
        VerizonUtils.postOnUiThread(new Runnable() {

            @Override
            public void run() {
                bannerListener = null;

                // Destroy any hanging references
                if (verizonInlineAd != null) {
                    verizonInlineAd.destroy();
                    verizonInlineAd = null;
                }
            }
        });
    }

    protected String getPlacementIdKey() {
        return PLACEMENT_ID_KEY;
    }

    protected String getSiteIdKey() {
        return SITE_ID_KEY;
    }

    protected String getWidthKey() {
        return WIDTH_KEY;
    }

    protected String getHeightKey() {
        return HEIGHT_KEY;
    }

    private void logAndNotifyBannerFailed(final MoPubLog.AdapterLogEvent event,
                                          final MoPubErrorCode errorCode) {

        MoPubLog.log(event, ADAPTER_NAME, errorCode.getIntCode(), errorCode);

        if (bannerListener != null) {
            bannerListener.onBannerFailed(errorCode);
        }
    }

    private class VerizonInlineAdFactoryListener implements InlineAdFactory.InlineAdFactoryListener {
        final CustomEventBannerListener listener = bannerListener;

        @Override
        public void onLoaded(final InlineAdFactory inlineAdFactory, final InlineAdView inlineAdView) {
            MoPubLog.log(LOAD_SUCCESS, ADAPTER_NAME);

            final CreativeInfo creativeInfo = verizonInlineAd == null ? null : verizonInlineAd.getCreativeInfo();
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Verizon creative info: " + creativeInfo);

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (internalView != null) {
                        internalView.addView(inlineAdView);
                    }

                    if (listener != null) {
						Map<String, Object> networkInfo = new HashMap<>();
						if(creativeInfo != null && creativeInfo.getCreativeId() != null) {
							networkInfo.put("vzCreativeId", creativeInfo.getCreativeId());
						}
						if(creativeInfo != null && creativeInfo.getDemandSource() != null) {
							networkInfo.put("vzDemandSource", creativeInfo.getDemandSource());
						}
						networkInfo.put("vzCreativeType", "Banner");

						notificationCenter.postNotification("AdDidLoadForVerizon", networkInfo);

						listener.onBannerLoaded(internalView);
                    }


                }
            });
        }

        @Override
        public void onCacheLoaded(final InlineAdFactory inlineAdFactory, final int numRequested,
                                  final int numReceived) {
        }

        @Override
        public void onCacheUpdated(final InlineAdFactory inlineAdFactory, final int cacheSize) {
        }

        @Override
        public void onError(final InlineAdFactory inlineAdFactory, final ErrorInfo errorInfo) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Unable to load Verizon banner due to error: "
                    + errorInfo.toString());

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    logAndNotifyBannerFailed(LOAD_FAILED, convertErrorInfoToMoPub(errorInfo));
                }
            });
        }
    }

    private class VerizonInlineAdListener implements InlineAdView.InlineAdListener {
        final CustomEventBannerListener listener = bannerListener;

        @Override
        public void onError(final InlineAdView inlineAdView, final ErrorInfo errorInfo) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Unable to show Verizon banner due to error: "
                    + errorInfo.toString());

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    logAndNotifyBannerFailed(SHOW_FAILED, convertErrorInfoToMoPub(errorInfo));
                }
            });
        }

        @Override
        public void onResized(final InlineAdView inlineAdView) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Verizon banner resized to: " +
                    inlineAdView.getAdSize().getWidth() + " by " +
                    inlineAdView.getAdSize().getHeight());
        }

        @Override
        public void onExpanded(final InlineAdView inlineAdView) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Verizon banner expanded");

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (listener != null) {
                        listener.onBannerExpanded();
                    }
                }
            });
        }

        @Override
        public void onCollapsed(final InlineAdView inlineAdView) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Verizon banner collapsed");

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (listener != null) {
                        listener.onBannerCollapsed();
                    }
                }
            });
        }

        @Override
        public void onClicked(final InlineAdView inlineAdView) {
            MoPubLog.log(CLICKED, ADAPTER_NAME);

            VerizonUtils.postOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (listener != null) {
                        listener.onBannerClicked();
                    }
                }
            });
        }

        @Override
        public void onAdLeftApplication(final InlineAdView inlineAdView) {
            // Only logging this event. No need to call bannerListener.onLeaveApplication()
            // because it's an alias for bannerListener.onBannerClicked()
            MoPubLog.log(WILL_LEAVE_APPLICATION, ADAPTER_NAME);
        }

        @Override
        public void onAdRefreshed(final InlineAdView inlineAdView) {
        }

        @Override
        public void onEvent(final InlineAdView inlineAdView, final String source, final String eventId,
                            final Map<String, Object> arguments) {
        }
    }
}
