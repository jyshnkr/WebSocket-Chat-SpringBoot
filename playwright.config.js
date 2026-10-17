const { defineConfig, devices } = require('playwright/test');

module.exports = defineConfig({
    testDir: './tests/browser/e2e',
    timeout: 30_000,
    globalTimeout: 180_000,
    expect: { timeout: 10_000 },
    fullyParallel: false,
    workers: 1,
    retries: 0,
    forbidOnly: Boolean(process.env.CI),
    reporter: [['list'], ['html', { open: 'never' }]],
    outputDir: 'test-results',
    use: {
        baseURL: 'http://127.0.0.1:6370',
        actionTimeout: 10_000,
        navigationTimeout: 20_000,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        launchOptions: {
            executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
        }
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
    webServer: {
        command: 'java -jar WebSocket-Chat-Application/target/WebSocket-Chat-Application-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=6370',
        cwd: __dirname,
        url: 'http://127.0.0.1:6370',
        reuseExistingServer: false,
        timeout: 60_000,
        gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 }
    }
});
