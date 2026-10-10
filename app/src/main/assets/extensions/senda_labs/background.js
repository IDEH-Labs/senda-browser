// The custom CSS and script from Senda Labs (Settings) are registered as content scripts: they run in the
// extension's isolated world, so a page cannot intercept or read them, and the page's CSP does not block them.
// The manifest says "incognito": "not_allowed": Gecko grants private browsing to built-in extensions unless
// they declare that, so this is what keeps the user's code out of private tabs.

const EXCLUDE = [
    '*://youtube.com/*', '*://*.youtube.com/*',
    '*://youtube-nocookie.com/*', '*://*.youtube-nocookie.com/*',
    '*://youtu.be/*'
];

let registered = [];
let queue = Promise.resolve();

async function apply(config) {
    for (const r of registered) {
        try { await r.unregister(); } catch (e) {}
    }
    registered = [];
    if (!config || config.type !== 'SET_LABS') return;
    const common = { matches: ['<all_urls>'], excludeMatches: EXCLUDE, runAt: 'document_idle' };
    try {
        if (config.css) registered.push(await browser.contentScripts.register({ ...common, css: [{ code: config.css }] }));
        if (config.js) registered.push(await browser.contentScripts.register({ ...common, js: [{ code: config.js }] }));
    } catch (e) {
        console.error('Senda Labs: could not register', e);
    }
}

// Changes are applied one after another, never interleaved
function enqueue(config) {
    queue = queue.then(() => apply(config));
}

try {
    const port = browser.runtime.connectNative('senda_labs');
    port.onMessage.addListener(enqueue);
} catch (e) {
    console.warn('Senda Labs: connectNative error', e);
}

browser.runtime.sendNativeMessage('senda_labs', { action: 'GET_LABS' })
    .then(enqueue)
    .catch((e) => console.warn('Senda Labs: sendNativeMessage error', e));
