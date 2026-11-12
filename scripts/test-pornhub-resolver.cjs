const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/pornhub-resolver.js'), 'utf8');
const origin = 'https://www.pornhub.com';
const api = origin + '/video/get_media?s=test';
const mp4 = 'https://cdn.example.com/video.mp4';

function page(variables, fetchImpl = async () => { throw new Error('Unexpected fetch'); }) {
    const calls = [];
    const delays = [];
    const context = vm.createContext({
        window: variables, location: {origin}, URL, AbortController,
        setTimeout(fn, delay) { delays.push(delay); return setTimeout(fn, delay); }, clearTimeout,
        fetch(url, options) { calls.push({url, options}); return fetchImpl(url, options); }
    });
    return {calls, delays, poll: () => JSON.parse(vm.runInContext(script, context))};
}

function response(body) {
    return {ok: true, status: 200, text: async () => body};
}

async function settled(browser) {
    for (let i = 0; i < 20; i++) {
        const value = browser.poll();
        if (!value.pending) return value;
        await new Promise(resolve => setImmediate(resolve));
    }
    assert.fail('Resolver did not finish');
}

(async () => {
    const waiting = page({});
    assert.deepEqual(waiting.poll(), {pending: true});
    waiting.poll();
    assert.equal(waiting.calls.length, 0);
    const lateVariables = {flashvars: {mediaDefinitions: []}};
    const late = page(lateVariables);
    assert.deepEqual(late.poll(), {pending: true});
    lateVariables.flashvars.mediaDefinitions.push({videoUrl: mp4, format: 'mp4', quality: 720});
    assert.deepEqual(await settled(late), {definitions: [{videoUrl: mp4, format: 'mp4', quality: 720}]});

    const browser = page({
        flashvars_42: {mediaDefinitions: [
            {videoUrl: api, format: 'mp4', quality: '720'},
            {videoUrl: api, format: 'mp4', quality: '720'},
            {videoUrl: 'https://cdn.example.com/master.m3u8', format: 'hls', quality: [360, 1080]}
        ], title: 'Never expose this', image: 'Never expose this'}
    }, async url => url === api
        ? response(JSON.stringify([[{videoUrl: mp4, format: 'mp4', quality: '720p', title: 'discard'}]]))
        : response('#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,BANDWIDTH=10\n../1080/index.m3u8?token=1\n' +
            '#EXT-X-STREAM-INF:BANDWIDTH=5,RESOLUTION=640x360\n//cdn.example.com/360.m3u8\n' +
            '#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=320x180\nhttps://127.0.0.1/private.m3u8\n'));
    assert.deepEqual(browser.poll(), {pending: true});
    assert.deepEqual(browser.poll(), {pending: true});
    const result = await settled(browser);
    assert.deepEqual(result, {definitions: [
        {videoUrl: 'https://cdn.example.com/1080/index.m3u8?token=1', format: 'hls', quality: 1080},
        {videoUrl: mp4, format: 'mp4', quality: 720},
        {videoUrl: 'https://cdn.example.com/360.m3u8', format: 'hls', quality: 360}
    ]});
    assert.equal(browser.calls.length, 2, 'Duplicate API and repeated polling must share requests');
    assert.deepEqual(browser.delays.sort(), [5000, 8000]);
    assert.ok(browser.calls.every(call => call.options.credentials === 'same-origin' &&
        call.options.redirect === 'error' && call.options.signal instanceof AbortSignal));
    assert.deepEqual(browser.poll(), result);
    assert.equal(browser.calls.length, 2);

    const media = await settled(page({flashvars: {mediaDefinitions: [
        {videoUrl: 'https://cdn.example.com/media.m3u8', format: 'hls', quality: 480}
    ]}}, async () => response('#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST')));
    assert.deepEqual(media, {definitions: [
        {videoUrl: 'https://cdn.example.com/media.m3u8', format: 'hls', quality: 480}
    ]});

    const rejected = ['http://cdn.example.com/a.mp4', 'https://user:pass@cdn.example.com/a.mp4',
        'https://cdn.example.com:8443/a.mp4', 'https://localhost/a.mp4', 'https://localhost./a.mp4', 'https://x.localhost/a.mp4',
        'https://127.0.0.1/a.mp4', 'https://2130706433/a.mp4', 'https://0x7f000001/a.mp4',
        'https://192.168.1.1/a.mp4', 'https://169.254.0.1/a.mp4', 'https://[::1]/a.mp4',
        'https://[fd00::1]/a.mp4', 'https://server.local/a.mp4', 'https://cdn.example.com/not-a-media-file',
        'https://cdn.example.com/list.m3u8', 'javascript:alert(1)'];
    const filtered = page({flashvars: {mediaDefinitions: rejected.map(videoUrl => ({videoUrl, format: 'mp4'})).concat([
        {videoUrl: 'https://cdn.example.com:443/video.mp4', format: 'mp4', quality: 720},
        {videoUrl: 'https://other.example.com/video/get_media', format: 'mp4'}
    ])}});
    const safe = await settled(filtered);
    assert.deepEqual(safe.definitions, [{videoUrl: mp4, format: 'mp4', quality: 720}]);
    assert.equal(filtered.calls.length, 0);
    assert.match(safe.error, /接口地址无效/);

    const failed = await settled(page({flashvars: {mediaDefinitions: [
        {videoUrl: api, format: 'mp4'}, {videoUrl: mp4, format: 'mp4', quality: 720}
    ]}}));
    assert.deepEqual(failed.definitions, [{videoUrl: mp4, format: 'mp4', quality: 720}]);
    assert.match(failed.error, /媒体接口读取失败/);

    const empty = await settled(page({flashvars: {mediaDefinitions: [{videoUrl: 'javascript:void(0)', format: 'mp4'}]}}));
    assert.deepEqual(empty.definitions, []);
    assert.ok(empty.error);
    console.log('Pornhub browser resolver checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
