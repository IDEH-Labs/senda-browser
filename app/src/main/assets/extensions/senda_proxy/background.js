let currentConfig = null;

// No request goes out until the proxy to use is known: otherwise, when opening Senda with Tor on, the first
// pages went out directly (with the real IP) while the configuration arrived from the app
let markReady;
const configReady = new Promise((resolve) => { markReady = resolve; });

// Senda's own Tor listens on a socket file only Senda can open, as in Tor Browser for Android: Gecko takes it as a
// file: URL in the host (nsSOCKSIOLayer.cpp). The port is not used for a socket, but the API wants one from 1 to 65535
function torSocket(config) {
    return typeof config.socket === 'string' && config.socket.startsWith('file:///') ? config.socket : null;
}

function proxyFor(config) {
    if (!config || config.mode === 'OFF') {
        return { type: 'direct' };
    }

    if (config.mode === 'TOR_ORBOT') {
        // Without the socket (it should not happen), a host that cannot connect: never a direct connection
        return {
            type: 'socks',
            host: torSocket(config) || 'file:///senda-tor-socket-unknown',
            port: 1,
            proxyDNS: true
        };
    }

    if (config.mode === 'CUSTOM_SOCKS5') {
        const host = config.host || '127.0.0.1';
        const port = parseInt(config.port, 10) || 9050;
        return {
            type: 'socks',
            host: host,
            port: port,
            proxyDNS: config.proxyDNS !== false
        };
    }

    if (config.mode === 'CUSTOM_HTTP') {
        const host = config.host || '127.0.0.1';
        const port = parseInt(config.port, 10) || 8080;
        return {
            type: 'http',
            host: host,
            port: port
        };
    }

    return { type: 'direct' };
}

// With Tor, requests wait while it connects: going through its port before then only gave a connection error, and
// going direct would reveal the real IP. "failed" stops waiting: through Tor's port, so it fails instead of leaking
let torWaiters = [];
const TOR_MAX_WAIT_MS = 120000;

function torStarting(config) {
    return config && config.mode === 'TOR_ORBOT' && config.torState === 'starting';
}

function releaseTorWaiters() {
    const waiting = torWaiters;
    torWaiters = [];
    waiting.forEach((release) => release());
}

function waitForTor() {
    return new Promise((resolve) => {
        const release = () => { clearTimeout(timer); resolve(); };
        const timer = setTimeout(() => {
            torWaiters = torWaiters.filter((r) => r !== release);
            resolve();
        }, TOR_MAX_WAIT_MS);
        torWaiters.push(release);
    });
}

// A Tor circuit per site, as in Tor Browser: Tor gives each SOCKS username its own circuit (IsolateSOCKSAuth, on by
// default), so the exit relay of one site never sees the others. The site is the page in the tab, not the server of
// each image or script. The password changes on every start of Senda, so each session gets new circuits
const circuitSession = Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');

function hostOf(url) {
    try { return new URL(url).host || url; } catch (e) { return ''; }
}

function firstPartyOf(requestInfo) {
    if (requestInfo.type === 'main_frame') return hostOf(requestInfo.url);
    const ancestors = requestInfo.frameAncestors;
    if (ancestors && ancestors.length) return hostOf(ancestors[ancestors.length - 1].url);
    return hostOf(requestInfo.documentUrl || requestInfo.originUrl || '') || '--unknown--';
}

function circuitFor(requestInfo) {
    // A SOCKS username has at most 255 bytes (RFC 1929); host names are ASCII (punycode)
    return ((requestInfo.incognito ? 'private:' : 'normal:') + firstPartyOf(requestInfo)).slice(0, 255);
}

async function handleProxyRequest(requestInfo) {
    if (!currentConfig) await configReady;
    if (torStarting(currentConfig)) await waitForTor();
    const proxy = proxyFor(currentConfig);
    // Only Senda's own Tor: another SOCKS proxy could refuse a username and leave the person with no connection
    if (currentConfig.mode === 'TOR_ORBOT') {
        proxy.username = circuitFor(requestInfo);
        proxy.password = circuitSession;
    }
    return proxy;
}

// 1. Register the active proxy listener for all URLs
try {
    if (browser.proxy && browser.proxy.onRequest) {
        browser.proxy.onRequest.addListener(handleProxyRequest, { urls: ['<all_urls>'] });
        console.log('Senda Proxy: onRequest listener activo');
    }
} catch (e) {
    console.error('Senda Proxy: Error en onRequest listener:', e);
}

// WebRTC can reveal the real IP even when the site goes through Tor or a proxy: with a proxy on, WebRTC is
// only allowed through the proxy
function applyWebRtcPolicy(config) {
    try {
        const setting = browser.privacy && browser.privacy.network && browser.privacy.network.webRTCIPHandlingPolicy;
        if (!setting) return;
        if (config && config.mode !== 'OFF') {
            setting.set({ value: 'proxy_only' }).catch(() => {});
        } else {
            setting.clear({}).catch(() => {});
        }
    } catch (e) {
        console.warn('Senda Proxy: WebRTC policy warning:', e);
    }
}

function applyProxy(config) {
    if (!config) return;
    currentConfig = config;
    markReady();
    if (!torStarting(config)) releaseTorWaiters();
    applyWebRtcPolicy(config);

    // 2. Global configuration through browser.proxy.settings
    try {
        if (browser.proxy && browser.proxy.settings) {
            if (config.mode === 'OFF') {
                browser.proxy.settings.set({ value: { proxyType: 'none' } }).catch(() => {});
            } else if (config.mode === 'TOR_ORBOT') {
                // proxy.settings cannot hold a socket (ext-proxy.js rejects a file: URL): Gecko's own setting, which
                // Senda sets to Tor's socket when it starts with Tor, is left as it is
                browser.proxy.settings.clear({}).catch(() => {});
            } else if (config.mode === 'CUSTOM_SOCKS5') {
                const host = config.host || '127.0.0.1';
                const port = parseInt(config.port, 10) || 9050;
                browser.proxy.settings.set({
                    value: {
                        proxyType: 'manual',
                        socks: host + ':' + port,
                        socksVersion: 5,
                        proxyDNS: config.proxyDNS !== false
                    }
                }).catch(() => {});
            } else if (config.mode === 'CUSTOM_HTTP') {
                const host = config.host || '127.0.0.1';
                const port = parseInt(config.port, 10) || 8080;
                browser.proxy.settings.set({
                    value: {
                        proxyType: 'manual',
                        http: host + ':' + port,
                        ssl: host + ':' + port
                    }
                }).catch(() => {});
            }
        }
    } catch (e) {
        console.warn('Senda Proxy: proxy.settings.set warning:', e);
    }
}

function rememberAndApply(msg) {
    if (msg && msg.type === 'SET_PROXY') {
        applyProxy(msg);
        // Tor's readiness belongs to this session: the saved copy is only the mode
        browser.storage.local.set({ currentProxy: Object.assign({}, msg, { torState: 'starting' }) });
    }
}

// 3. Persistent native port connection
try {
    const port = browser.runtime.connectNative('senda_proxy');
    port.onMessage.addListener(rememberAndApply);
} catch (e) {
    console.warn('Senda Proxy: connectNative error', e);
}

// 4. Last saved configuration: available instantly at startup
browser.storage.local.get('currentProxy').then((res) => {
    if (res && res.currentProxy && !currentConfig) {
        applyProxy(res.currentProxy);
    }
}).catch(() => {});

// 5. Ask the app: the source of truth
try {
    browser.runtime.sendNativeMessage('senda_proxy', { action: 'GET_INITIAL_PROXY' })
        .then(rememberAndApply)
        .catch((err) => console.log('Senda Proxy: sendNativeMessage err', err));
} catch (e) {
    console.warn('Senda Proxy: sendNativeMessage error', e);
}

// If the app does not answer (it should not happen), do not block browsing forever: with no known
// configuration, browse directly, the same as with the proxy off
setTimeout(() => {
    if (!currentConfig) {
        applyProxy({ type: 'SET_PROXY', mode: 'OFF' });
    }
}, 8000);
