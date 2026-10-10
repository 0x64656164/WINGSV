package wings.v.byedpi;

/**
 * Shares whether a ByeDPI front proxy will serve the current tunnel session with
 * the code that builds the xray config and applies per-app routing.
 *
 * <p>ProxyTunnelService owns the proxy and runs in the same tunnel process as
 * XrayConfigFactory and XrayVpnService, so a static flag carries the signal
 * without a callback or a prefs round-trip.
 *
 * <p>The flag is decision, not observation. The config is pre-built on a worker
 * thread that races the proxy start, so polling the process would always report
 * dead and would strip the divert out of the config; ProxyTunnelService instead
 * settles the question up front and publishes the answer here.
 *
 * <p>A config that references the ByeDPI SOCKS outbound while nothing listens on
 * it does not fail loudly: xray accepts the tag, the listed apps get no route,
 * and they appear simply broken. Gating emission on this flag keeps those apps on
 * the normal tunnel instead.
 */
public final class ByeDpiRuntimeState {

    private static volatile boolean frontProxyActive;

    private ByeDpiRuntimeState() {}

    public static boolean isFrontProxyActive() {
        return frontProxyActive;
    }

    public static void setFrontProxyActive(boolean active) {
        frontProxyActive = active;
    }
}
