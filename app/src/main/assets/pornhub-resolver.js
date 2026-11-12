(() => {
    const key = '__secretDownloaderState';
    if (window[key]) return JSON.stringify(window[key].result || {pending: true});

    const variables = Object.keys(window).filter(name => /^flashvars(?:_\d+)?$/.test(name));
    if (!variables.length) return JSON.stringify({pending: true});
    const definitions = variables.reduce((items, name) => {
        try { return items.concat(flatten(window[name] && window[name].mediaDefinitions)); }
        catch (_) { return items; }
    }, []).slice(0, 64);
    if (!definitions.length) return JSON.stringify({pending: true});
    const state = window[key] = {result: null};
    const errors = [];
    const requests = new Map();

    function safeUrl(value, base) {
        try {
            const url = new URL(value, base);
            const host = url.hostname.toLowerCase().replace(/\.$/, '');
            // CDN hosts use DNS names; excluding IP literals also blocks alternate private-IP encodings.
            if (url.protocol !== 'https:' || url.username || url.password || url.port ||
                !host || host.includes(':') || /^[\d.]+$/.test(host) ||
                host === 'localhost' || host.endsWith('.localhost') ||
                host.endsWith('.local') || host.endsWith('.internal')) return null;
            url.hash = '';
            return url;
        } catch (_) {
            return null;
        }
    }

    function height(value) {
        const match = String(value || '').match(/\d+/);
        return match ? Number(match[0]) : 0;
    }

    function flatten(value, depth = 0) {
        if (!Array.isArray(value) || depth > 8) return [];
        return value.reduce((items, item) => items.concat(Array.isArray(item) ? flatten(item, depth + 1) : [item]), []);
    }

    function fetchText(url, timeout) {
        if (!requests.has(url.href)) {
            requests.set(url.href, (async () => {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), timeout);
                try {
                    const response = await fetch(url.href, {
                        credentials: 'same-origin', redirect: 'error', signal: controller.signal
                    });
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    return await response.text();
                } finally {
                    clearTimeout(timer);
                }
            })());
        }
        return requests.get(url.href);
    }

    async function resolve(definition, expandApi = true) {
        if (!definition || typeof definition !== 'object') return [];
        const url = safeUrl(definition.videoUrl);
        if (!url) return [];
        const quality = height(definition.quality);
        const format = String(definition.format || '').toLowerCase();
        if (url.pathname === '/video/get_media') {
            if (!expandApi || url.origin !== location.origin) {
                errors.push('媒体接口地址无效');
                return [];
            }
            try {
                const media = JSON.parse(await fetchText(url, 5000));
                if (!Array.isArray(media)) throw new Error('Invalid media list');
                const expanded = await Promise.all(flatten(media).slice(0, 64).map(item => resolve(item, false)));
                return expanded.flat();
            } catch (_) {
                errors.push('媒体接口读取失败');
                return [];
            }
        }
        if ((format === 'mp4' || !format) && /\.mp4$/i.test(url.pathname)) {
            return [{videoUrl: url.href, format: 'mp4', quality}];
        }
        if ((format && format !== 'hls') || !/\.m3u8$/i.test(url.pathname)) return [];
        const original = {videoUrl: url.href, format: 'hls', quality};
        try {
            const playlist = await fetchText(url, 8000);
            if (!/^\s*#EXTM3U/.test(playlist)) throw new Error('Invalid playlist');
            const lines = playlist.split(/\r?\n/).map(line => line.trim());
            const variants = [];
            let resolution = 0;
            for (const line of lines) {
                if (line.startsWith('#EXT-X-STREAM-INF:')) {
                    const match = line.match(/(?:^|[:,])\s*RESOLUTION=(\d+)x(\d+)/i);
                    resolution = match ? Number(match[2]) : 0;
                } else if (line && !line.startsWith('#')) {
                    const variant = safeUrl(line, url.href);
                    if (resolution && variant) variants.push({videoUrl: variant.href, format: 'hls', quality: resolution});
                    resolution = 0;
                }
            }
            if (variants.length) return variants;
            if (lines.some(line => line.startsWith('#EXTINF:'))) return [original];
            throw new Error('Missing video playlist');
        } catch (_) {
            errors.push('播放列表读取失败，保留原始播放地址');
            return [original];
        }
    }

    (async () => {
        try {
            const expanded = (await Promise.all(definitions.map(item => resolve(item)))).flat();
            const seen = new Set();
            const result = expanded.filter(item => {
                const id = item.format + ':' + item.videoUrl;
                if (seen.has(id)) return false;
                seen.add(id);
                return true;
            }).sort((a, b) => b.quality - a.quality || Number(b.format === 'mp4') - Number(a.format === 'mp4'));
            state.result = {definitions: result};
            if (errors.length || !result.length) {
                state.result.error = [...new Set(errors)].join('；') || '页面未提供可用的视频地址';
            }
        } catch (_) {
            state.result = {definitions: [], error: '浏览器媒体解析失败'};
        }
    })();
    return JSON.stringify({pending: true});
})()
