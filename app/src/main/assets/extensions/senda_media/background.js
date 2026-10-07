// Only watches network responses: when a page downloads a video (MP4, WebM, HLS) Senda is told
// the video's address and the page's, to show it on the TV while mirroring. Nothing is injected into
// pages and no request is modified

const VIDEO_TYPES = /^(video\/(mp4|webm|quicktime|x-matroska|x-m4v)|application\/(vnd\.apple\.mpegurl|x-mpegurl)|audio\/mpegurl)/i;
const VIDEO_PATH = /\.(mp4|m4v|webm|mov|mkv|m3u8)$/i;
// Loose chunks of a streaming video: they cannot be played on their own
const SEGMENT_PATH = /\.(ts|m4s|aac|vtt|webvtt)$/i;

let port = null;
try {
    port = browser.runtime.connectNative('senda_media');
} catch (e) {
    console.warn('Senda Media: connectNative error', e);
}

function header(headers, name) {
    const h = (headers || []).find((x) => x.name.toLowerCase() === name);
    return h ? h.value : null;
}

// Headers the page used to request the video: some servers only deliver it with their Referer
const requestHeaders = new Map();

browser.webRequest.onSendHeaders.addListener((details) => {
    const referer = header(details.requestHeaders, 'referer');
    if (referer) requestHeaders.set(details.requestId, referer);
}, { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other'] }, ['requestHeaders']);

browser.webRequest.onHeadersReceived.addListener((details) => {
    const referer = requestHeaders.get(details.requestId) || null;
    requestHeaders.delete(details.requestId);
    if (!port || details.tabId < 0) return;
    if (details.statusCode < 200 || details.statusCode >= 300) return;
    let path;
    try {
        path = new URL(details.url).pathname;
    } catch (e) {
        return;
    }
    if (SEGMENT_PATH.test(path)) return;
    const type = (header(details.responseHeaders, 'content-type') || '').split(';')[0].trim();
    if (!VIDEO_TYPES.test(type) && !VIDEO_PATH.test(path)) return;
    // Page where the video is shown: the top-level one, even if the player is in an iframe from another site
    const ancestors = details.frameAncestors || [];
    const page = ancestors.length > 0 ? ancestors[ancestors.length - 1].url : (details.documentUrl || details.originUrl);
    if (!page) return;
    let length = -1;
    const range = header(details.responseHeaders, 'content-range');
    if (range && range.includes('/')) {
        length = parseInt(range.split('/').pop(), 10);
    } else {
        length = parseInt(header(details.responseHeaders, 'content-length') || '-1', 10);
    }
    try {
        port.postMessage({
            type: 'MEDIA_FOUND',
            page: page,
            url: details.url,
            mime: type,
            length: isNaN(length) ? -1 : length,
            referer: referer
        });
    } catch (e) {
        console.warn('Senda Media: postMessage error', e);
    }
}, { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other'] }, ['responseHeaders']);

// Requests that never get a response must not stay in memory
browser.webRequest.onErrorOccurred.addListener((details) => {
    requestHeaders.delete(details.requestId);
}, { urls: ['<all_urls>'] });
