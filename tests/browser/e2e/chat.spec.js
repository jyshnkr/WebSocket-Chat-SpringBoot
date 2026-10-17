const { randomUUID } = require('node:crypto');
const { test, expect } = require('playwright/test');

const password = 'Browser-test-pass123';
const uniqueName = () => 'user_' + randomUUID().replaceAll('-', '').slice(0, 14);

async function fillCredentials(page, username, secret = password) {
    await page.locator('#name').fill(username);
    await page.locator('#password').fill(secret);
}

async function createAccount(page, username, secret = password) {
    await page.goto('/');
    await fillCredentials(page, username, secret);
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
}

async function expectMessage(page, sender, content) {
    const message = page.locator('.chat-message').filter({
        has: page.getByText(content, { exact: true })
    });
    await expect(message).toHaveCount(1);
    await expect(message.locator('span')).toHaveText(sender);
    await expect(message.locator('p')).toHaveText(content);
}

test('two users exchange literal markup with authenticated sender identities and no persistent token', async ({ page, browser, baseURL }) => {
    const otherContext = await browser.newContext({ baseURL });
    const otherPage = await otherContext.newPage();
    const firstName = uniqueName();
    const secondName = uniqueName();
    const markup = '<img src=x onerror="window.markupExecuted=true"> & <script>alert(1)</script>';
    try {
        await createAccount(page, firstName);
        await createAccount(otherPage, secondName);
        await expect(page.locator('#messageArea')).toContainText(secondName + ' joined!');
        await page.locator('#message').fill(markup);
        await page.locator('#message').press('Enter');
        await expectMessage(page, firstName, markup);
        await expectMessage(otherPage, firstName, markup);
        await expect(page.locator('#message')).toHaveValue('');
        await otherPage.locator('#message').fill('Hello from the second user');
        await otherPage.getByRole('button', { name: 'Send', exact: true }).click();
        await expectMessage(page, secondName, 'Hello from the second user');
        await expectMessage(otherPage, secondName, 'Hello from the second user');
        for (const participant of [page, otherPage]) {
            await expect(participant.locator('#messageArea img, #messageArea script')).toHaveCount(0);
            expect(await participant.evaluate(() => ({
                local: localStorage.length,
                session: sessionStorage.length,
                executed: Boolean(window.markupExecuted)
            }))).toEqual({ local: 0, session: 0, executed: false });
        }
        await page.reload();
        await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeEnabled();
        await expect(page.locator('#chat-page')).toBeHidden();
    } finally {
        await otherContext.close();
    }
});

test('registration and login errors allow a successful sign-in retry', async ({ page }) => {
    const username = uniqueName();
    await createAccount(page, username);
    await page.reload();
    await fillCredentials(page, username);
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page.getByRole('alert')).toHaveText('Username is already taken');
    await fillCredentials(page, username, 'secret7');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password');
    await fillCredentials(page, username);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
    await expect(page.getByRole('alert')).toBeHidden();
});

test('delayed registration and login disable both buttons and ignore repeated submissions', async ({ page }) => {
    const pending = [];
    let requestCount = 0;
    await page.route('**/api/auth/*', async (route) => {
        requestCount++;
        await new Promise((resolve) => pending.push(resolve));
        await route.continue();
    });
    await page.goto('/');
    const username = uniqueName();
    await fillCredentials(page, username);
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect.poll(() => requestCount).toBe(1);
    for (const expectedRequests of [1, 2]) {
        await expect(page.getByRole('button', { name: 'Create Account' })).toBeDisabled();
        await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeDisabled();
        await page.locator('#usernameForm').dispatchEvent('submit');
        await page.locator('#register').dispatchEvent('click');
        expect(requestCount).toBe(expectedRequests);
        pending.shift()();
        if (expectedRequests === 1) await expect.poll(() => requestCount).toBe(2);
    }
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
    expect(requestCount).toBe(2);
    await expect(page.locator('.event-message').filter({ hasText: username + ' joined!' })).toHaveCount(1);
});

test('STOMP rejection returns to sign-in, preserves its error through close, and permits retry', async ({ page }) => {
    const username = uniqueName();
    await createAccount(page, username);
    await page.reload();
    await page.route('**/api/auth/login', async (route) => {
        const response = await route.fetch();
        const body = await response.json();
        await route.fulfill({ response, json: { ...body, accessToken: 'invalid-token' } });
    });
    await fillCredentials(page, username);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('Your session has expired. Please sign in again.');
    await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeEnabled();
    await expect(page.locator('#chat-page')).toBeHidden();
    await page.unroute('**/api/auth/login');
    await fillCredentials(page, username);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
    await page.locator('#message').fill('Retry succeeded');
    await page.locator('#message').press('Enter');
    await expectMessage(page, username, 'Retry succeeded');
});

test('the password UI rejects over 72 UTF-8 bytes and accepts the exact multibyte boundary', async ({ page }) => {
    await page.goto('/');
    const username = uniqueName();
    let requests = 0;
    page.on('request', (request) => {
        if (request.url().includes('/api/auth/')) requests++;
    });
    await fillCredentials(page, username, 'é'.repeat(37));
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page.getByRole('alert')).toHaveText('Password must be at least 8 characters and at most 72 UTF-8 bytes.');
    expect(requests).toBe(0);
    await fillCredentials(page, username, 'é'.repeat(36));
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
});
