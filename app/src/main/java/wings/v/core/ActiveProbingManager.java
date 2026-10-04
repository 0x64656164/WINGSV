package wings.v.core;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import wings.v.ActiveProbingSettingsActivity;
import wings.v.R;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings({
    "PMD.DoNotUseThreads",
    "PMD.AvoidCatchingGenericException",
    "PMD.CommentRequired",
    "PMD.LawOfDemeter",
    "PMD.MethodArgumentCouldBeFinal",
    "PMD.LocalVariableCouldBeFinal",
    "PMD.LongVariable",
    "PMD.OnlyOneReturn",
})
public final class ActiveProbingManager {

    public static final String KEY_OPEN_SETTINGS = "pref_open_active_probing_settings";
    public static final String KEY_OPEN_TARGETS = "pref_open_active_probing_targets";
    public static final String KEY_TUNNEL_ENABLED = "pref_active_probing_tunnel_enabled";
    public static final String KEY_VK_TURN_ENABLED = "pref_active_probing_vk_turn_enabled";
    public static final String KEY_BACKGROUND_ENABLED = "pref_active_probing_background_enabled";
    public static final String KEY_XRAY_FALLBACK_BACKEND = "pref_active_probing_xray_fallback_backend";
    public static final String KEY_RESTORE_BACKEND = "pref_active_probing_restore_backend";
    public static final String KEY_URLS = "pref_active_probing_urls";
    public static final String KEY_INTERVAL_SECONDS = "pref_active_probing_interval_seconds";
    public static final String KEY_TIMEOUT_SECONDS = "pref_active_probing_timeout_seconds";

    private static final String NOTIFICATION_CHANNEL_ID = "wingsv_active_probing";
    private static final int NOTIFICATION_ID = 4;
    private static final int DEFAULT_INTERVAL_SECONDS = 20;
    private static final int DEFAULT_TIMEOUT_SECONDS = 2;

    private ActiveProbingManager() {}

    public static final class Settings {

        public boolean tunnelEnabled;
        public boolean vkTurnEnabled;
        public boolean backgroundEnabled;
        public BackendType xrayFallbackBackend = BackendType.VK_TURN_WIREGUARD;
        public String rawUrls = serializeUrls(defaultUrls(null));
        public List<String> urls = defaultUrls(null);
        public int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        public long intervalMs() {
            return TimeUnit.SECONDS.toMillis(Math.max(1, intervalSeconds));
        }

        public long timeoutMs() {
            return TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds));
        }
    }

    public static final class ProbeResult {

        public final boolean hasUsablePhysicalNetwork;
        public final int totalCount;
        public final int reachableCount;
        public final List<String> failedTargets;
        public final List<ProbeDetail> details;

        ProbeResult(
            boolean hasUsablePhysicalNetwork,
            int totalCount,
            int reachableCount,
            List<String> failedTargets,
            List<ProbeDetail> details
        ) {
            this.hasUsablePhysicalNetwork = hasUsablePhysicalNetwork;
            this.totalCount = Math.max(totalCount, 0);
            this.reachableCount = Math.max(reachableCount, 0);
            this.failedTargets = failedTargets != null ? new ArrayList<>(failedTargets) : new ArrayList<>();
            this.details = details != null ? new ArrayList<>(details) : new ArrayList<>();
        }

        public boolean shouldFallback() {
            return hasUsablePhysicalNetwork && totalCount > 0 && reachableCount <= 0;
        }

        @NonNull
        public String failedTargetsSummary() {
            if (failedTargets.isEmpty()) {
                return "";
            }
            ArrayList<String> labels = new ArrayList<>();
            for (String target : failedTargets) {
                if (TextUtils.isEmpty(target)) {
                    continue;
                }
                String normalized = target.trim();
                if (normalized.startsWith("https://")) {
                    normalized = normalized.substring("https://".length());
                } else if (normalized.startsWith("http://")) {
                    normalized = normalized.substring("http://".length());
                }
                int slashIndex = normalized.indexOf('/');
                if (slashIndex >= 0) {
                    normalized = normalized.substring(0, slashIndex);
                }
                if (!TextUtils.isEmpty(normalized)) {
                    labels.add(normalized);
                }
            }
            if (labels.isEmpty()) {
                return "";
            }
            if (labels.size() == 1) {
                return labels.get(0);
            }
            if (labels.size() == 2) {
                return labels.get(0) + ", " + labels.get(1);
            }
            return labels.get(0) + ", " + labels.get(1) + " +" + (labels.size() - 2);
        }
    }

    public static final class ProbeDetail {

        public final String url;
        public final String host;
        public final String transport;
        public final long elapsedMs;
        public final boolean ok;
        public final String reason;

        ProbeDetail(String url, String host, String transport, long elapsedMs, boolean ok, String reason) {
            this.url = url != null ? url : "";
            this.host = !TextUtils.isEmpty(host) ? host : this.url;
            this.transport = !TextUtils.isEmpty(transport) ? transport : "unknown";
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.ok = ok;
            this.reason = !TextUtils.isEmpty(reason) ? reason : "exception-unknown";
        }

        @NonNull
        public String formatLogLine() {
            return (
                "active-probe host=" +
                host +
                " net=" +
                transport +
                " protect=no elapsed_ms=" +
                elapsedMs +
                " result=" +
                (ok ? "ok" : "fail") +
                " reason=" +
                reason
            );
        }
    }

    public static Settings getSettings(@Nullable Context context) {
        Settings settings = new Settings();
        if (context == null) {
            return settings;
        }
        Context appContext = context.getApplicationContext();
        settings.tunnelEnabled = prefs(appContext).getBoolean(KEY_TUNNEL_ENABLED, false);
        settings.vkTurnEnabled = prefs(appContext).getBoolean(KEY_VK_TURN_ENABLED, false);
        settings.backgroundEnabled = prefs(appContext).getBoolean(KEY_BACKGROUND_ENABLED, false);
        settings.xrayFallbackBackend = normalizeXrayFallbackBackend(
            BackendType.fromPrefValue(
                prefs(appContext).getString(KEY_XRAY_FALLBACK_BACKEND, BackendType.VK_TURN_WIREGUARD.prefValue)
            )
        );
        String storedUrls = prefs(appContext).getString(KEY_URLS, null);
        settings.rawUrls = storedUrls == null ? serializeUrls(defaultUrls(appContext)) : trim(storedUrls);
        settings.urls = parseUrls(appContext, settings.rawUrls);
        settings.intervalSeconds = parseInt(
            prefs(appContext).getString(KEY_INTERVAL_SECONDS, String.valueOf(DEFAULT_INTERVAL_SECONDS)),
            DEFAULT_INTERVAL_SECONDS
        );
        settings.timeoutSeconds = parseInt(
            prefs(appContext).getString(KEY_TIMEOUT_SECONDS, String.valueOf(DEFAULT_TIMEOUT_SECONDS)),
            DEFAULT_TIMEOUT_SECONDS
        );
        return settings;
    }

    public static boolean isTunnelProbingAvailable(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        BackendType backendType = XrayStore.getBackendType(context);
        return backendType != null && (backendType.usesXrayCore() || backendType.isPlainBackend());
    }

    @NonNull
    public static BackendType normalizeXrayFallbackBackend(@Nullable BackendType backendType) {
        if (backendType == BackendType.AMNEZIAWG || backendType == BackendType.AMNEZIAWG_PLAIN) {
            return BackendType.AMNEZIAWG;
        }
        return BackendType.VK_TURN_WIREGUARD;
    }

    @NonNull
    public static String getBackendLabel(@Nullable Context context, @Nullable BackendType backendType) {
        BackendType resolved = backendType == null ? BackendType.VK_TURN_WIREGUARD : backendType;
        if (context == null) {
            if (resolved != null && resolved.usesXrayCore()) {
                return "Xray";
            }
            if (resolved == BackendType.AMNEZIAWG) {
                return "VK TURN + AmneziaWG";
            }
            if (resolved == BackendType.AMNEZIAWG_PLAIN) {
                return "AmneziaWG";
            }
            if (resolved == BackendType.WIREGUARD) {
                return "WireGuard";
            }
            return "VK TURN + WireGuard";
        }
        if (resolved != null && resolved.usesXrayCore()) {
            return context.getString(R.string.backend_xray_title);
        }
        if (resolved == BackendType.AMNEZIAWG) {
            return context.getString(R.string.backend_amneziawg_title);
        }
        if (resolved == BackendType.AMNEZIAWG_PLAIN) {
            return context.getString(R.string.backend_amneziawg_plain_title);
        }
        if (resolved == BackendType.WIREGUARD) {
            return context.getString(R.string.backend_wireguard_title);
        }
        if (resolved == BackendType.WB_STREAM) {
            return context.getString(R.string.backend_wb_stream_title);
        }
        if (resolved == BackendType.WB_STREAM_AMNEZIAWG) {
            return context.getString(R.string.backend_wb_stream_amneziawg_title);
        }
        return context.getString(R.string.backend_vk_turn_wireguard_title);
    }

    @Nullable
    public static BackendType getRestoreBackend(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        String rawValue = prefs(context.getApplicationContext()).getString(KEY_RESTORE_BACKEND, null);
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        BackendType backendType = BackendType.fromPrefValue(rawValue);
        return backendType == BackendType.VK_TURN_WIREGUARD && !TextUtils.equals(rawValue, backendType.prefValue)
            ? null
            : backendType;
    }

    public static void setRestoreBackend(@Nullable Context context, @Nullable BackendType backendType) {
        if (context == null) {
            return;
        }
        if (backendType == null) {
            clearRestoreBackend(context);
            return;
        }
        prefs(context.getApplicationContext()).edit().putString(KEY_RESTORE_BACKEND, backendType.prefValue).commit();
    }

    public static void clearRestoreBackend(@Nullable Context context) {
        if (context == null) {
            return;
        }
        prefs(context.getApplicationContext()).edit().remove(KEY_RESTORE_BACKEND).commit();
    }

    @NonNull
    public static String buildUrlsSummary(@NonNull Context context, @Nullable String rawValue) {
        List<String> urls = parseUrls(null, rawValue);
        if (urls.isEmpty()) {
            return context.getString(wings.v.R.string.common_no_sites);
        }
        if (urls.size() == 1) {
            return urls.get(0);
        }
        return urls.size() + " URL • " + urls.get(0) + " • " + urls.get(1);
    }

    @NonNull
    public static List<String> getUrls(@Nullable Context context) {
        return getSettings(context).urls;
    }

    public static void saveUrls(@Nullable Context context, @Nullable List<String> urls) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        prefs(appContext).edit().putString(KEY_URLS, serializeUrls(urls)).commit();
        ActiveProbingBackgroundScheduler.refresh(appContext);
    }

    @NonNull
    public static String normalizeUrl(@Nullable String rawValue) {
        List<String> urls = parseUrls(null, rawValue);
        return urls.isEmpty() ? "" : urls.get(0);
    }

    @NonNull
    public static ProbeResult runDirectProbes(@Nullable Context context, @Nullable Settings settings) {
        Settings resolvedSettings = settings != null ? settings : getSettings(context);
        List<String> urls =
            resolvedSettings.urls != null ? new ArrayList<>(resolvedSettings.urls) : defaultUrls(context);
        if (context == null || urls.isEmpty()) {
            return new ProbeResult(false, urls.size(), 0, urls, null);
        }
        Network network = findUsablePhysicalNetwork(context.getApplicationContext());
        if (network == null) {
            return new ProbeResult(false, urls.size(), 0, urls, null);
        }

        int timeoutMs = (int) Math.max(500L, resolvedSettings.timeoutMs());
        String transport = transportLabel(context.getApplicationContext(), network);
        int successCount = 0;
        ArrayList<String> failedTargets = new ArrayList<>();
        ArrayList<ProbeDetail> details = new ArrayList<>();
        try (ExecutorScope executorScope = new ExecutorScope(Math.max(1, Math.min(urls.size(), 4)))) {
            ArrayList<Future<ProbeDetail>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(
                    executorScope.executor.submit(new ProbeTask(network, url, hostOf(url), transport, timeoutMs))
                );
            }
            for (int index = 0; index < urls.size(); index++) {
                ProbeDetail detail;
                try {
                    detail = futures.get(index).get(timeoutMs + 750L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    detail = new ProbeDetail(
                        urls.get(index),
                        hostOf(urls.get(index)),
                        transport,
                        0L,
                        false,
                        "interrupted"
                    );
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    detail = new ProbeDetail(
                        urls.get(index),
                        hostOf(urls.get(index)),
                        transport,
                        timeoutMs + 750L,
                        false,
                        classifyReason(cause)
                    );
                } catch (java.util.concurrent.TimeoutException ignored) {
                    detail = new ProbeDetail(
                        urls.get(index),
                        hostOf(urls.get(index)),
                        transport,
                        timeoutMs + 750L,
                        false,
                        "timeout"
                    );
                }
                details.add(detail);
                if (detail.ok) {
                    successCount++;
                } else {
                    failedTargets.add(urls.get(index));
                }
            }
        }
        for (ProbeDetail detail : details) {
            try {
                ProxyTunnelService.writeRuntimeLogLine(detail.formatLogLine());
            } catch (Exception ignored) {}
        }
        return new ProbeResult(true, urls.size(), successCount, failedTargets, details);
    }

    private static final class ExecutorScope implements AutoCloseable {

        private final ExecutorService executor;

        private ExecutorScope(int threadCount) {
            this.executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    public static void showTunnelFallbackNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType backendType
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_tunnel_title,
            R.string.active_probing_notification_tunnel_text,
            result,
            fromBackend,
            backendType
        );
    }

    public static void showBackgroundFallbackNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType backendType
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_background_title,
            R.string.active_probing_notification_background_text,
            result,
            null,
            backendType
        );
    }

    public static void showRestoreNotification(
        @Nullable Context context,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType restoreBackend
    ) {
        showTriggerNotification(
            context,
            R.string.active_probing_notification_restore_title,
            R.string.active_probing_notification_restore_text,
            result,
            fromBackend,
            restoreBackend
        );
    }

    private static void showTriggerNotification(
        @Nullable Context context,
        int titleRes,
        int textRes,
        @Nullable ProbeResult result,
        @Nullable BackendType fromBackend,
        @Nullable BackendType backendType
    ) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationManager notificationManager = appContext.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        createNotificationChannel(appContext, notificationManager);
        String failedTargets = result != null ? result.failedTargetsSummary() : "";
        String resolvedFailedTargets = TextUtils.isEmpty(failedTargets)
            ? appContext.getString(R.string.active_probing_notification_targets_unknown)
            : failedTargets;
        String text =
            titleRes == R.string.active_probing_notification_background_title
                ? appContext.getString(textRes, resolvedFailedTargets, getBackendLabel(appContext, backendType))
                : appContext.getString(
                      textRes,
                      resolvedFailedTargets,
                      getBackendLabel(appContext, fromBackend),
                      getBackendLabel(appContext, backendType)
                  );
        Intent openIntent = ActiveProbingSettingsActivity.createIntent(appContext).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        PendingIntent pendingIntent = PendingIntent.getActivity(
            appContext,
            401,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(appContext.getString(titleRes))
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    private static void createNotificationChannel(Context context, NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.active_probing_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.enableVibration(true);
        channel.setDescription(context.getString(R.string.active_probing_notification_channel_description));
        notificationManager.createNotificationChannel(channel);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static Network findUsablePhysicalNetwork(Context context) {
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (isUsablePhysicalNetwork(connectivityManager, activeNetwork)) {
                return activeNetwork;
            }
            Network underlyingNetwork = findVpnUnderlyingNetwork(connectivityManager, activeNetwork);
            if (underlyingNetwork != null) {
                return underlyingNetwork;
            }
            Network[] networks = connectivityManager.getAllNetworks();
            return selectPreferredNetwork(connectivityManager, networks);
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static Network findVpnUnderlyingNetwork(
        @Nullable ConnectivityManager connectivityManager,
        @Nullable Network activeNetwork
    ) {
        if (connectivityManager == null || activeNetwork == null) {
            return null;
        }
        try {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return null;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return null;
            }
            List<Network> underlyingNetworks = capabilities.getUnderlyingNetworks();
            if (underlyingNetworks == null) {
                return null;
            }
            return selectPreferredNetwork(connectivityManager, underlyingNetworks.toArray(new Network[0]));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Network selectPreferredNetwork(
        @Nullable ConnectivityManager connectivityManager,
        @Nullable Network[] candidates
    ) {
        if (connectivityManager == null || candidates == null) {
            return null;
        }
        Network best = null;
        int bestRank = Integer.MAX_VALUE;
        try {
            for (Network network : candidates) {
                if (network == null) {
                    continue;
                }
                NetworkCapabilities capabilities;
                try {
                    capabilities = connectivityManager.getNetworkCapabilities(network);
                } catch (Exception ignored) {
                    continue;
                }
                if (!isUsablePhysicalNetwork(capabilities)) {
                    continue;
                }
                int rank = transportRank(capabilities);
                if (rank < bestRank) {
                    best = network;
                    bestRank = rank;
                    if (bestRank == 0) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    private static int transportRank(@Nullable NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return Integer.MAX_VALUE;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return 0;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return 1;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return 2;
        }
        return 3;
    }

    private static boolean isUsablePhysicalNetwork(
        @Nullable ConnectivityManager connectivityManager,
        @Nullable Network network
    ) {
        if (connectivityManager == null || network == null) {
            return false;
        }
        try {
            return isUsablePhysicalNetwork(connectivityManager.getNetworkCapabilities(network));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isUsablePhysicalNetwork(@Nullable NetworkCapabilities capabilities) {
        if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false;
        }
        boolean physicalTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH);
        if (!physicalTransport || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            return false;
        }
        return true;
    }

    @NonNull
    private static List<String> parseUrls(@Nullable Context context, @Nullable String rawValue) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = rawValue == null ? "" : rawValue.replace('\r', '\n');
        String[] parts = normalized.split("[\\n,;]+");
        for (String part : parts) {
            String candidate = trim(part);
            if (TextUtils.isEmpty(candidate)) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.US);
            if (lower.startsWith("https://") || lower.startsWith("http://")) {
                result.add(candidate);
            }
        }
        if (result.isEmpty() && TextUtils.isEmpty(trim(rawValue))) {
            result.addAll(defaultUrls(context));
        }
        return new ArrayList<>(result);
    }

    @NonNull
    private static List<String> defaultUrls(@Nullable Context context) {
        ArrayList<String> defaults = new ArrayList<>();
        if (context != null) {
            String[] values = context.getResources().getStringArray(R.array.active_probing_default_urls);
            for (String value : values) {
                String normalized = normalizeUrl(value);
                if (!TextUtils.isEmpty(normalized)) {
                    defaults.add(normalized);
                }
            }
        }
        if (defaults.isEmpty()) {
            defaults.add("https://1.1.1.1");
            defaults.add("https://github.com");
        }
        return defaults;
    }

    @NonNull
    private static String serializeUrls(@Nullable List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String url : urls) {
            String candidate = normalizeUrl(url);
            if (!TextUtils.isEmpty(candidate)) {
                normalized.add(candidate);
            }
        }
        return TextUtils.join("\n", normalized);
    }

    private static int parseInt(@Nullable String rawValue, int fallback) {
        if (TextUtils.isEmpty(rawValue)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(rawValue.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return AppPrefs.defaultSharedPreferences(context);
    }

    @NonNull
    private static String transportLabel(@Nullable Context context, @Nullable Network network) {
        if (context == null || network == null) {
            return "unknown";
        }
        try {
            ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities =
                connectivityManager != null ? connectivityManager.getNetworkCapabilities(network) : null;
            if (capabilities == null) {
                return "unknown";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "wifi";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "cell";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ethernet";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                return "bt";
            }
            return "other";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    @NonNull
    private static String hostOf(@Nullable String urlValue) {
        String value = urlValue != null ? urlValue.trim() : "";
        int schemeIndex = value.indexOf("://");
        if (schemeIndex >= 0) {
            value = value.substring(schemeIndex + 3);
        }
        int slashIndex = value.indexOf('/');
        if (slashIndex >= 0) {
            value = value.substring(0, slashIndex);
        }
        return TextUtils.isEmpty(value) && urlValue != null ? urlValue : value;
    }

    @NonNull
    private static String classifyReason(@Nullable Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.NoRouteToHostException) {
            return "unreachable";
        }
        if (error instanceof java.net.ConnectException) {
            String message = String.valueOf(error.getMessage()).toLowerCase(Locale.US);
            if (message.contains("refused")) {
                return "refused";
            }
            if (message.contains("timed out")) {
                return "timeout";
            }
            return "unreachable";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "tls-error";
        }
        if (error != null) {
            String message = String.valueOf(error.getMessage());
            if (message.contains("EPERM") || message.contains("bind")) {
                return "bind";
            }
            return "exception-" + error.getClass().getSimpleName();
        }
        return "exception-unknown";
    }

    private static final class ProbeTask implements Callable<ProbeDetail> {

        private final Network network;
        private final String urlValue;
        private final String host;
        private final String transport;
        private final int timeoutMs;

        ProbeTask(Network network, String urlValue, String host, String transport, int timeoutMs) {
            this.network = network;
            this.urlValue = urlValue;
            this.host = host;
            this.transport = transport;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public ProbeDetail call() {
            long startMs = SystemClock.elapsedRealtime();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) network.openConnection(new URL(urlValue));
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("Connection", "close");
                boolean ok = connection.getResponseCode() > 0;
                long elapsedMs = SystemClock.elapsedRealtime() - startMs;
                return new ProbeDetail(urlValue, host, transport, elapsedMs, ok, ok ? "ok" : "bad-status");
            } catch (Exception error) {
                long elapsedMs = SystemClock.elapsedRealtime() - startMs;
                return new ProbeDetail(urlValue, host, transport, elapsedMs, false, classifyReason(error));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
