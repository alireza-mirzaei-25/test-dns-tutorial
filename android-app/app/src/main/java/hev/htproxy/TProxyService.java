package hev.htproxy;

/**
 * JNI wrapper for hev-socks5-tunnel (tun2socks implementation).
 * This class provides the native interface to the high-performance tun2socks library.
 */
public class TProxyService {

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    /**
     * Start the tun2socks service.
     * @param configPath Path to the YAML configuration file
     * @param tunFd File descriptor of the TUN interface from VpnService
     */
    public native void TProxyStartService(String configPath, int tunFd);

    /**
     * Stop the tun2socks service.
     */
    public native void TProxyStopService();

    /**
     * Get traffic statistics.
     * @return long array: [tx_packets, tx_bytes, rx_packets, rx_bytes]
     */
    public native long[] TProxyGetStats();

    // Singleton instance
    private static TProxyService instance;

    public static synchronized TProxyService getInstance() {
        if (instance == null) {
            instance = new TProxyService();
        }
        return instance;
    }

    private TProxyService() {
        // Private constructor for singleton
    }
}
