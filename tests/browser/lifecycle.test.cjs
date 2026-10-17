const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = readFileSync(path.join(__dirname,
    '../../WebSocket-Chat-Application/src/main/resources/static/js/main.js'), 'utf8');

function element() {
    const classes = new Set();
    const listeners = new Map();
    return {
        value: '', textContent: '', disabled: false, style: {}, children: [],
        classList: {
            add: (name) => classes.add(name),
            remove: (name) => classes.delete(name),
            contains: (name) => classes.has(name)
        },
        addEventListener: (name, listener) => listeners.set(name, listener),
        appendChild(child) { this.children.push(child); },
        dispatch(name) {
            const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
            listeners.get(name)(event);
            return event;
        }
    };
}

function browser() {
    const elements = new Map();
    const requests = [];
    const clients = [];
    const select = (selector) => {
        if (!elements.has(selector)) elements.set(selector, element());
        return elements.get(selector);
    };
    const context = {
        document: {
            querySelector: select,
            createElement: element,
            createTextNode: (text) => ({ textContent: text })
        },
        TextEncoder,
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
        },
        SockJS: function () {
            this.close = () => { this.closed = true; if (this.onclose) this.onclose(); };
        },
        Stomp: {
            over(socket) {
                const client = {
                    socket, connected: false, sends: [],
                    connect(headers, success, error) {
                        this.headers = headers;
                        this.success = () => { this.connected = true; success(); };
                        this.error = error;
                        socket.onclose = () => { this.connected = false; error('Connection lost'); };
                    },
                    subscribe(destination, callback) { this.receive = callback; },
                    send(destination, headers, body) {
                        if (this.beforeSend) this.beforeSend();
                        if (!this.connected || this.failSend) throw new Error('Connection lost');
                        this.sends.push({ destination, body: JSON.parse(body) });
                    },
                    disconnect() { this.connected = false; socket.close(); }
                };
                clients.push(client);
                return client;
            }
        }
    };
    vm.runInNewContext(source, context);
    select('#name').value = 'alice';
    select('#password').value = 'password123';
    return {
        select, requests, clients,
        login: () => select('#usernameForm').dispatch('submit'),
        register: () => select('#register').dispatch('click'),
        send: () => select('#messageForm').dispatch('submit'),
        async respond(index, data = { accessToken: 'test-token' }, ok = true) {
            requests[index].resolve({ ok, json: async () => data });
            await new Promise(setImmediate);
        }
    };
}

test('only one authentication attempt can run until its socket connects', async () => {
    const page = browser();
    assert.equal(page.login().defaultPrevented, true);
    assert.equal(page.select('#usernameForm button[type="submit"]').disabled, true);
    assert.equal(page.select('#register').disabled, true);
    page.login();
    page.register();
    assert.equal(page.requests.length, 1);
    await page.respond(0);
    page.login();
    assert.equal(page.requests.length, 1);
    assert.equal(page.clients.length, 1);
    page.clients[0].success();
    assert.equal(page.select('#messageForm button[type="submit"]').disabled, false);
});

test('Unauthorized returns to sign-in and survives close; old callbacks cannot affect a retry', async () => {
    const page = browser();
    page.login();
    await page.respond(0, { accessToken: 'first-token' });
    const first = page.clients[0];
    first.success();
    first.error({ headers: { message: 'Unauthorized' } });
    const rejection = page.select('#auth-error').textContent;
    assert.match(rejection, /session.*expired/i);
    assert.equal(page.select('#username-page').classList.contains('hidden'), false);
    assert.equal(page.select('#chat-page').classList.contains('hidden'), true);
    assert.equal(first.socket.closed, true);
    first.socket.onclose();
    assert.equal(page.select('#auth-error').textContent, rejection);
    assert.equal(page.select('#register').disabled, false);

    page.select('#password').value = 'password123';
    page.login();
    await page.respond(1, { accessToken: 'second-token' });
    const second = page.clients[1];
    first.success();
    first.error({ headers: { message: 'Unauthorized' } });
    first.receive({ body: JSON.stringify({ sender: 'old', content: 'stale', type: 'CHAT' }) });
    assert.equal(first.sends.length, 1);
    assert.equal(page.select('#messageArea').children.length, 0);
    assert.equal(page.select('#auth-error').classList.contains('hidden'), true);
    second.success();
    assert.equal(second.headers.Authorization, 'Bearer second-token');
    assert.equal(second.sends.length, 1);
    assert.equal(page.select('#messageForm button[type="submit"]').disabled, false);
});

test('submitting before connection or after a send failure preserves the unsent text', async () => {
    const page = browser();
    page.select('#message').value = '  keep this draft  ';
    assert.equal(page.send().defaultPrevented, true);
    assert.equal(page.select('#message').value, '  keep this draft  ');
    page.login();
    await page.respond(0);
    assert.doesNotThrow(() => assert.equal(page.send().defaultPrevented, true));
    assert.equal(page.select('#message').value, '  keep this draft  ');
    const client = page.clients[0];
    client.success();
    client.failSend = true;
    assert.doesNotThrow(() => assert.equal(page.send().defaultPrevented, true));
    assert.equal(page.select('#message').value, '  keep this draft  ');
    assert.equal(page.select('#username-page').classList.contains('hidden'), false);
    assert.equal(client.socket.closed, true);
    assert.equal(client.sends.length, 1);
});

test('registration password validation requires eight characters and caps UTF-8 at 72 bytes', () => {
    for (const password of ['short12', 'é'.repeat(37), '🙂'.repeat(19)]) {
        const page = browser();
        page.select('#password').value = password;
        page.register();
        assert.equal(page.requests.length, 0, 'invalid password must not reach registration');
        assert.match(page.select('#auth-error').textContent, /8.*72.*UTF-8/);
    }
    for (const password of ['a'.repeat(72), 'é'.repeat(36), '🙂'.repeat(18)]) {
        const page = browser();
        page.select('#password').value = password;
        page.register();
        assert.equal(page.requests.length, 1, '72-byte password must be accepted');
    }
});

test('login submits a short seeded-account password while registration rejects it', async () => {
    const page = browser();
    page.select('#password').value = 'secret7';
    page.register();
    assert.equal(page.requests.length, 0);
    assert.match(page.select('#auth-error').textContent, /8.*72.*UTF-8/);
    page.login();
    assert.equal(page.requests.length, 1);
    assert.equal(page.requests[0].url, '/api/auth/login');
    assert.deepEqual(JSON.parse(page.requests[0].options.body), { username: 'alice', password: 'secret7' });
    await page.respond(0);
    page.clients[0].success();
    assert.equal(page.select('#messageForm button[type="submit"]').disabled, false);
});

test('a successful HTTP response without an access token returns to sign-in without opening a socket', async () => {
    const page = browser();
    page.login();
    await page.respond(0, {});
    assert.equal(page.clients.length, 0);
    assert.equal(page.select('#register').disabled, false);
    assert.equal(page.select('#auth-error').classList.contains('hidden'), false);
});

test('a transport error during send still prevents navigation and preserves the draft', async () => {
    const page = browser();
    page.login();
    await page.respond(0);
    const client = page.clients[0];
    client.success();
    page.select('#message').value = 'my unsent draft';
    client.beforeSend = () => client.error('Connection lost');
    assert.doesNotThrow(() => assert.equal(page.send().defaultPrevented, true));
    assert.equal(page.select('#message').value, 'my unsent draft');
    assert.equal(page.select('#register').disabled, false);
});

test('network and API failures release the attempt so registration can be retried', async () => {
    const page = browser();
    page.register();
    page.requests[0].reject(new Error('Offline'));
    await new Promise(setImmediate);
    assert.equal(page.select('#register').disabled, false);
    assert.match(page.select('#auth-error').textContent, /Could not reach the server/);
    page.register();
    await page.respond(1, { detail: 'Username is already taken' }, false);
    assert.equal(page.select('#auth-error').textContent, 'Username is already taken');
    page.login();
    await page.respond(2);
    page.clients[0].success();
    assert.equal(page.select('#messageForm button[type="submit"]').disabled, false);
});

test('a failed connection cannot announce or render later, and a retry announces only once', async () => {
    const page = browser();
    page.login();
    await page.respond(0);
    const first = page.clients[0];
    first.error('Connection lost');
    page.select('#password').value = 'password123';
    page.login();
    first.success();
    assert.equal(first.socket.closed, true);
    assert.equal(first.sends.length, 0);
    await page.respond(1);
    const second = page.clients[1];
    second.success();
    second.success();
    assert.equal(second.sends.length, 1);
    page.select('#message').value = 'hello';
    page.send();
    assert.deepEqual(second.sends[1], {
        destination: '/app/chat.sendMessage', body: { content: 'hello', type: 'CHAT' }
    });
    assert.equal(page.select('#message').value, '');
    second.socket.onclose();
    assert.equal(page.select('#username-page').classList.contains('hidden'), false);
    assert.equal(page.select('#messageForm button[type="submit"]').disabled, true);
    assert.match(page.select('#auth-error').textContent, /sign in again/);
    assert.equal(page.clients.length, 2);
    assert.equal(page.requests.length, 2);
});
